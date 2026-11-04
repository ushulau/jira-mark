package dev.vspex.jira.mark.profile;

import dev.vspex.jira.mark.remote.OptionLabels;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves defaults < input file < command-line values into a validated offline draft. */
public final class DraftResolver {
  /** Reserved top-level input key listing aliases to empty on update. */
  public static final String CLEAR_KEY = "$clear";

  private DraftResolver() {}

  public static Map<String, Object> resolve(
      Profile profile, Map<String, Object> input, List<String> arguments) {
    Map<String, Object> values = values(profile, input, arguments);
    List<String> missing = new ArrayList<>();
    Map<String, Object> fields = new LinkedHashMap<>();
    List<String> cleared = clears(input);
    profile
        .fields()
        .forEach(
            (alias, field) -> {
              Object value = values.get(alias);
              if (cleared.contains(alias)) {
                if (field.required()) {
                  throw new ProfileException(
                      "Field '" + alias + "' is required and cannot be cleared");
                }
                fields.put(
                    field.id(),
                    field.type().equals("array") || field.type().equals("string-list")
                        ? List.of()
                        : null);
                return;
              }
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
    if (!cleared.isEmpty()) {
      draft.put("clear", List.copyOf(cleared));
    }
    return draft;
  }

  /** Validated alias values before the missing-required check, for explicit prompting. */
  public static Map<String, Object> values(
      Profile profile, Map<String, Object> input, List<String> arguments) {
    Map<String, Object> values = new LinkedHashMap<>();
    profile.fields().forEach((alias, field) -> values.put(alias, field.defaultValue()));
    for (var entry : input.entrySet()) {
      if (entry.getKey().equals(CLEAR_KEY)) {
        continue;
      }
      requireField(profile, entry.getKey());
      values.put(entry.getKey(), entry.getValue());
    }
    for (String argument : arguments) {
      int split = argument.indexOf('=');
      if (split < 1) {
        throw new ProfileException("--field must use alias=value");
      }
      String alias = argument.substring(0, split);
      if (alias.equals(CLEAR_KEY) || alias.startsWith("$")) {
        throw new ProfileException("Field aliases may not start with $");
      }
      values.put(
          alias, requireField(profile, alias).parseArgument(argument.substring(split + 1), alias));
    }
    // Offline label resolution: bare labels become {id} where the profile lists allowedValues.
    for (var entry : profile.fields().entrySet()) {
      String alias = entry.getKey();
      FieldDefinition field = entry.getValue();
      Object value = values.get(alias);
      if (value != null && (field.type().equals("object") || field.type().equals("array"))) {
        values.put(alias, OptionLabels.resolveOffline(field, value, alias));
      }
    }
    profile.fields().forEach((alias, field) -> field.validate(values.get(alias), alias));
    List<String> cleared = clears(input);
    for (String alias : cleared) {
      requireField(profile, alias);
    }
    return values;
  }

  /** Aliases listed under the reserved {@code $clear} key, in input order. */
  public static List<String> clears(Map<String, Object> input) {
    Object raw = input.get(CLEAR_KEY);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)
        || list.stream().anyMatch(item -> !(item instanceof String))) {
      throw new ProfileException("$clear must be a list of field aliases");
    }
    var result = new ArrayList<String>();
    for (Object item : list) {
      String alias = ((String) item).trim();
      if (alias.isEmpty() || alias.startsWith("$")) {
        throw new ProfileException("Invalid alias in $clear: " + item);
      }
      if (!result.contains(alias)) {
        result.add(alias);
      }
    }
    return List.copyOf(result);
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
