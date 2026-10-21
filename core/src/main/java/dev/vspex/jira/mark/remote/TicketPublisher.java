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
    if (!List.of("ask", "create", "update", "cancel").contains(onExisting)) {
      throw new ProfileException("--on-existing must be ask, create, update, or cancel");
    }
    var draft = DraftResolver.resolve(profile, input, args);
    try (var journal =
        PublicationJournal.open(
            historyDirectory, gateway, connection, profilesDirectory, profile, inputFile, draft)) {
      return publishLocked(journal, profile, input, args, onExisting, choose);
    }
  }

  private JiraGateway.CreatedIssue publishLocked(
      PublicationJournal journal,
      Profile profile,
      Map<String, Object> input,
      List<String> args,
      String policy,
      Function<String, Decision> choose)
      throws IOException {
    Entry previous = journal.confirmedEntry();
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
