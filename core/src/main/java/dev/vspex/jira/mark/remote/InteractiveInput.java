package dev.vspex.jira.mark.remote;

import dev.vspex.jira.mark.profile.DraftResolver;
import dev.vspex.jira.mark.profile.Profile;
import dev.vspex.jira.mark.profile.ProfileException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/** Explicit prompting over the same field definitions used by file input. */
public final class InteractiveInput {
  private InteractiveInput() {}

  public static boolean confirmPull(
      TicketPuller.Preview preview, BufferedReader reader, PrintStream prompts) {
    if (reader == null) return false;
    prompts.print(
        "Pull "
            + preview.key()
            + " into the input file; changed fields: "
            + String.join(", ", preview.changedFields())
            + (preview.changesBinding() ? "; link input to this issue" : "")
            + ". Overwrite local values? [y/N]: ");
    prompts.flush();
    try {
      String answer = reader.readLine();
      return answer != null
          && (answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"));
    } catch (IOException failure) {
      throw new JiraFailure(
          JiraFailure.Code.CANCELLED,
          "Cannot read pull confirmation; the input file was not changed");
    }
  }

  public static TicketPublisher.Decision chooseExisting(
      String issueKey, BufferedReader reader, PrintStream prompts) {
    if (reader == null) return TicketPublisher.Decision.CANCEL;
    try {
      while (true) {
        prompts.print(
            "Input was previously published as "
                + issueKey
                + ". [u]pdate existing, [n]ew issue, or [c]ancel (default): ");
        prompts.flush();
        String answer = reader.readLine();
        if (answer == null || answer.isBlank() || answer.equals(":cancel")) {
          return TicketPublisher.Decision.CANCEL;
        }
        switch (answer.trim().toLowerCase(java.util.Locale.ROOT)) {
          case "u", "update" -> {
            return TicketPublisher.Decision.UPDATE;
          }
          case "n", "new", "create" -> {
            return TicketPublisher.Decision.CREATE;
          }
          case "c", "cancel" -> {
            return TicketPublisher.Decision.CANCEL;
          }
          default -> prompts.println("Choose update, new, or cancel.");
        }
      }
    } catch (IOException failure) {
      throw new JiraFailure(
          JiraFailure.Code.CANCELLED, "Cannot read the publication choice; no issue was submitted");
    }
  }

  public static Map<String, Object> complete(
      Profile profile,
      Map<String, Object> input,
      List<String> args,
      JiraGateway.Metadata metadata,
      BufferedReader reader,
      PrintStream prompts)
      throws IOException {
    if (reader == null) {
      throw new ProfileException(
          "Interactive mode requires a terminal; use --input for automation");
    }
    var values = DraftResolver.values(profile, input, args);
    if (metadata != null) {
      for (var remote : metadata.fields().values()) {
        if (remote.required()
            && !remote.hasDefault()
            && !List.of("project", "issuetype").contains(remote.id())
            && profile.fields().values().stream()
                .noneMatch(field -> field.id().equals(remote.id()))) {
          throw new JiraFailure(
              JiraFailure.Code.REMOTE_VALIDATION,
              "Add the Jira-required field '" + remote.id() + "' to the profile");
        }
      }
    }
    for (var entry : profile.fields().entrySet()) {
      var field = entry.getValue();
      var remote = metadata == null ? null : metadata.fields().get(field.id());
      boolean required =
          field.required() || remote != null && remote.required() && !remote.hasDefault();
      while (required && JiraWorkflow.empty(values.get(entry.getKey()))) {
        prompts.print(entry.getKey() + " (" + field.type() + "): ");
        prompts.flush();
        String answer = reader.readLine();
        if (answer == null || answer.equals(":cancel")) {
          throw new JiraFailure(
              JiraFailure.Code.CANCELLED, "Input cancelled; no issue was submitted");
        }
        try {
          Object value = field.parseArgument(answer, entry.getKey());
          field.validate(value, entry.getKey());
          values.put(entry.getKey(), value);
        } catch (ProfileException failure) {
          prompts.println("Invalid value; expected " + field.type());
        }
      }
    }
    return values;
  }
}
