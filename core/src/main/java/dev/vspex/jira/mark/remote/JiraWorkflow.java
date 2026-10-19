package dev.vspex.jira.mark.remote;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.REMOTE_VALIDATION;

import dev.vspex.jira.mark.profile.DraftResolver;
import dev.vspex.jira.mark.profile.Profile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared metadata validation and deliberate single-submission orchestration. */
public final class JiraWorkflow {
  private final JiraGateway gateway;
  private final JiraFieldEncoder encoder;

  public JiraWorkflow(JiraGateway gateway, JiraFieldEncoder encoder) {
    this.gateway = gateway;
    this.encoder = encoder;
  }

  public Map<String, Object> validate(
      Profile profile, Map<String, Object> input, List<String> args) {
    var draft = DraftResolver.resolve(profile, input, args);
    var metadata = gateway.metadata(profile.project(), profile.issueType());
    var localValues = (Map<?, ?>) draft.get("fields");
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("project", Map.of("id", metadata.projectId()));
    fields.put("issuetype", Map.of("id", metadata.issueTypeId()));
    profile
        .fields()
        .forEach(
            (alias, definition) -> {
              var remote = metadata.fields().get(definition.id());
              if (remote == null || !remote.settable()) {
                throw new JiraFailure(
                    REMOTE_VALIDATION, "Field '" + alias + "' is unavailable for creation");
              }
              Object value = localValues.get(definition.id());
              if (value != null && remote.required() && empty(value)) {
                throw new JiraFailure(
                    REMOTE_VALIDATION, "Jira requires a nonempty field '" + alias + "'");
              }
              if (value != null) {
                fields.put(definition.id(), encoder.encode(remote, value, alias));
              }
            });
    for (var field : metadata.fields().values()) {
      if (field.id().equals("project") || field.id().equals("issuetype")) {
        continue;
      }
      if (field.required()
          && (!field.hasDefault() || fields.containsKey(field.id()))
          && empty(fields.get(field.id()))) {
        String alias =
            profile.fields().entrySet().stream()
                .filter(entry -> entry.getValue().id().equals(field.id()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(field.id());
        throw new JiraFailure(REMOTE_VALIDATION, "Jira requires field '" + alias + "'");
      }
    }
    return Map.of("fields", fields);
  }

  public JiraGateway.CreatedIssue create(
      Profile profile, Map<String, Object> input, List<String> args) {
    return gateway.create(validate(profile, input, args));
  }

  public static boolean empty(Object value) {
    return value == null
        || value instanceof String s && s.isBlank()
        || value instanceof java.util.Collection<?> c && c.isEmpty()
        || value instanceof Map<?, ?> m && m.isEmpty();
  }
}
