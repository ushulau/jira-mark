package dev.vspex.jira.mark.remote;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;

import dev.vspex.jira.mark.profile.DraftResolver;
import dev.vspex.jira.mark.profile.Profile;
import dev.vspex.jira.mark.profile.ProfileException;
import dev.vspex.jira.mark.remote.PublicationJournal.Entry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Tracks successful submissions locally and requires an explicit decision for repeated input. */
public final class TicketPublisher {
  public enum Decision {
    CREATE,
    UPDATE,
    CANCEL
  }

  private final JiraGateway gateway;
  private final JiraWorkflow workflow;

  public TicketPublisher(JiraGateway gateway, JiraFieldEncoder encoder) {
    this.gateway = gateway;
    workflow = new JiraWorkflow(gateway, encoder);
  }

  public JiraGateway.CreatedIssue publish(
      Path historyDirectory,
      String connection,
      Path profilesDirectory,
      Profile profile,
      Path inputFile,
      Map<String, Object> input,
      List<String> args,
      String onExisting,
      Function<String, Decision> choose)
      throws IOException {
    return submit(
        historyDirectory,
        connection,
        profilesDirectory,
        profile,
        inputFile,
        input,
        args,
        onExisting,
        choose,
        null,
        false);
  }

  /** Updates an explicit or locally bound issue; never creates an issue. */
  public JiraGateway.CreatedIssue update(
      Path historyDirectory,
      String connection,
      Path profilesDirectory,
      Profile profile,
      Path inputFile,
      Map<String, Object> input,
      List<String> args,
      String issue)
      throws IOException {
    return submit(
        historyDirectory,
        connection,
        profilesDirectory,
        profile == null ? TicketPuller.standardProfile() : profile,
        inputFile,
        input,
        args,
        "update",
        key -> Decision.UPDATE,
        issue,
        profile == null);
  }

  private JiraGateway.CreatedIssue submit(
      Path historyDirectory,
      String connection,
      Path profilesDirectory,
      Profile profile,
      Path inputFile,
      Map<String, Object> input,
      List<String> args,
      String onExisting,
      Function<String, Decision> choose,
      String explicitIssue,
      boolean standardFields)
      throws IOException {
    if (!List.of("ask", "create", "update", "cancel").contains(onExisting)) {
      throw new ProfileException("--on-existing must be ask, create, update, or cancel");
    }
    var draft = DraftResolver.resolve(profile, input, args);
    try (var journal =
        PublicationJournal.open(
            historyDirectory, gateway, connection, profilesDirectory, profile, inputFile, draft)) {
      return publishLocked(
          journal, profile, input, args, onExisting, choose, explicitIssue, standardFields);
    }
  }

  private JiraGateway.CreatedIssue publishLocked(
      PublicationJournal journal,
      Profile profile,
      Map<String, Object> input,
      List<String> args,
      String policy,
      Function<String, Decision> choose,
      String explicitIssue,
      boolean standardFields)
      throws IOException {
    Entry previous = journal.confirmedEntry();
    if (explicitIssue != null) {
      if (!explicitIssue.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+"))
        throw new ProfileException("--issue must be a Jira issue key");
      if (previous != null && !previous.key().equalsIgnoreCase(explicitIssue))
        throw new ProfileException(
            "Input is bound to "
                + previous.key()
                + "; pull the intended issue into a separate file before updating");
      var remote = gateway.readIssue(explicitIssue, java.util.Set.of());
      if (!standardFields
          && (!(profile.project().equals(remote.projectId())
                  || profile.project().equals(remote.projectKey()))
              || !(profile.issueType().equals(remote.issueTypeId())
                  || profile.issueType().equals(remote.issueTypeName()))))
        throw new JiraFailure(
            REMOTE_VALIDATION, "Issue project or type does not match the profile");
      if (!remote.key().equalsIgnoreCase(explicitIssue))
        throw new JiraFailure(REMOTE_VALIDATION, "Jira returned a different issue than requested");
      if (previous == null)
        previous = new Entry("confirmed", remote.key(), remote.projectId(), remote.issueTypeId());
    }
    if (previous == null && (policy.equals("update") || policy.equals("cancel")))
      throw new ProfileException(
          "No matching publication record exists; no issue was submitted. "
              + "Use jiraUpdate --issue=<key> to select an update target explicitly, "
              + "or pull with the same profile, connection, and input path first");
    Decision decision = Decision.CREATE;
    if (previous != null) {
      decision =
          policy.equals("ask")
              ? choose.apply(previous.key())
              : Decision.valueOf(policy.toUpperCase(java.util.Locale.ROOT));
      if (decision == null || decision == Decision.CANCEL) {
        throw new JiraFailure(
            CANCELLED,
            "Input was previously published as "
                + previous.key()
                + "; no issue was submitted. Choose --on-existing=create or --on-existing=update to continue");
      }
    }
    boolean updating = decision == Decision.UPDATE;
    Map<String, Object> payload =
        updating
            ? workflow.validateUpdate(
                profile, input, args, previous.key(), previous.project(), previous.issueType())
            : workflow.validate(profile, input, args);
    var fields = (Map<?, ?>) payload.get("fields");
    String project =
        updating ? previous.project() : (String) ((Map<?, ?>) fields.get("project")).get("id");
    String issueType =
        updating ? previous.issueType() : (String) ((Map<?, ?>) fields.get("issuetype")).get("id");
    journal.write(
        new Entry(
            updating ? "pending-update" : "pending-create",
            updating ? previous.key() : "",
            project,
            issueType));
    JiraGateway.CreatedIssue result;
    try {
      result = updating ? gateway.update(previous.key(), payload) : gateway.create(payload);
    } catch (JiraFailure failure) {
      if (failure.code() != UNCERTAIN_CREATION && failure.code() != UNCERTAIN_UPDATE) {
        // A definite rejection permits a future retry; ambiguous outcomes retain their pending
        // marker.
        try {
          if (previous == null) journal.clear();
          else journal.write(previous);
        } catch (IOException restoreFailure) {
          // Keep the conservative pending state if the local filesystem cannot be restored.
        }
      }
      throw failure;
    }
    try {
      journal.write(new Entry("confirmed", result.key(), project, issueType));
    } catch (IOException failure) {
      throw new JiraFailure(
          updating ? UNCERTAIN_UPDATE : UNCERTAIN_CREATION,
          "Jira accepted issue "
              + result.key()
              + " but local history could not be saved; inspect Jira before retrying");
    }
    return result;
  }
}
