package dev.vspex.jira.mark.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves defaults < input file < command-line values into a validated offline draft. */
public final class DraftResolver {
  private DraftResolver() {}

  public static Map<String, Object> resolve(
      Profile profile, Map<String, Object> input, List<String> arguments) {
    Map<String, Object> values = values(profile, input, arguments);
    List<String> missing = new ArrayList<>();
    Map<String, Object> fields = new LinkedHashMap<>();
    profile
        .fields()
        .forEach(
            (alias, field) -> {
              Object value = values.get(alias);
              if (field.required() && empty(value)) {
                missing.add(alias);
              }
              if (value != null) {
                fields.put(field.id(), value);
              }
            });
    if (!missing.isEmpty()) {
      throw new ProfileException("Missing required fields: " + String.join(", ", missing));
    }
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("schemaVersion", 1);
    draft.put("profile", profile.name());
    draft.put("project", profile.project());
    draft.put("issueType", profile.issueType());
    draft.put("fields", fields);
    return draft;
  }

  /** Validated alias values before the missing-required check, for explicit prompting. */
  public static Map<String, Object> values(
      Profile profile, Map<String, Object> input, List<String> arguments) {
    Map<String, Object> values = new LinkedHashMap<>();
    profile.fields().forEach((alias, field) -> values.put(alias, field.defaultValue()));
    for (var entry : input.entrySet()) {
      requireField(profile, entry.getKey());
      values.put(entry.getKey(), entry.getValue());
    }
    for (String argument : arguments) {
      int split = argument.indexOf('=');
      if (split < 1) {
        throw new ProfileException("--field must use alias=value");
      }
      String alias = argument.substring(0, split);
      values.put(
          alias, requireField(profile, alias).parseArgument(argument.substring(split + 1), alias));
    }
    profile.fields().forEach((alias, field) -> field.validate(values.get(alias), alias));
    return values;
  }

  private static FieldDefinition requireField(Profile profile, String alias) {
    FieldDefinition field = profile.fields().get(alias);
    if (field == null) {
      throw new ProfileException("Unknown field alias: " + alias);
    }
    return field;
  }

  private static boolean empty(Object value) {
    return value == null
        || value instanceof String s && s.isBlank()
        || value instanceof Collection<?> c && c.isEmpty()
        || value instanceof Map<?, ?> m && m.isEmpty();
  }
}
