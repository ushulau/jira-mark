package dev.vspex.jira.mark.remote;

import dev.vspex.jira.mark.profile.FieldDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves option labels to Jira option ids.
 *
 * <p>Input accepts {@code {id}}, {@code {value}}, or a bare label string. Resolution matches the
 * exact label first, then a case-insensitive trimmed match. Zero or ambiguous matches fail with the
 * list of valid labels.
 */
public final class OptionLabels {
  private OptionLabels() {}

  /** Resolves one value against live metadata choices ({@code id}/{@code value}/{@code name}). */
  public static Object resolveLive(JiraGateway.Field field, Object value, String alias) {
    if (value == null || field.choices().isEmpty()) {
      return value;
    }
    if (value instanceof List<?> list) {
      var resolved = new ArrayList<>();
      for (Object item : list) {
        resolved.add(resolveOneLive(field, item, alias));
      }
      return List.copyOf(resolved);
    }
    return resolveOneLive(field, value, alias);
  }

  private static Object resolveOneLive(JiraGateway.Field field, Object value, String alias) {
    if (value instanceof String label) {
      return Map.of("id", lookupLive(field, label, alias));
    }
    if (value instanceof Map<?, ?> map) {
      if (map.get("id") instanceof String) {
        return value;
      }
      Object label = map.get("value");
      if (label == null) {
        label = map.get("name");
      }
      if (label instanceof String text) {
        var resolved = new LinkedHashMap<String, Object>();
        resolved.put("id", lookupLive(field, text, alias));
        return resolved;
      }
    }
    return value;
  }

  private static String lookupLive(JiraGateway.Field field, String label, String alias) {
    List<String> valid = new ArrayList<>();
    String exact = null;
    String folded = null;
    String wanted = label.trim();
    for (Object choice : field.choices()) {
      if (!(choice instanceof Map<?, ?> map)) {
        continue;
      }
      Object text = map.get("value");
      if (text == null) {
        text = map.get("name");
      }
      Object id = map.get("id");
      if (!(text instanceof String option) || !(id instanceof String optionId)) {
        continue;
      }
      valid.add(option);
      if (option.equals(label)) {
        exact = optionId;
      } else if (folded == null && option.trim().equalsIgnoreCase(wanted)) {
        folded = optionId;
      }
    }
    if (exact != null) {
      return exact;
    }
    if (folded != null) {
      // Ambiguity on folded match fails only when several options fold to the same input.
      long matches =
          valid.stream().filter(option -> option.trim().equalsIgnoreCase(wanted)).count();
      if (matches == 1) {
        return folded;
      }
    }
    throw new JiraFailure(
        JiraFailure.Code.INVALID_INPUT,
        "Field '"
            + alias
            + "' ('"
            + label
            + "') is not an allowed value. Allowed: "
            + String.join(", ", valid));
  }

  /** Offline resolution against a profile's {@code allowedValues} id/value objects. */
  public static Object resolveOffline(FieldDefinition definition, Object value, String alias) {
    var entries = definition.optionEntries();
    if (value == null || entries.isEmpty()) {
      return value;
    }
    if (value instanceof List<?> list) {
      var resolved = new ArrayList<>();
      for (Object item : list) {
        resolved.add(resolveOneOffline(entries, item));
      }
      return List.copyOf(resolved);
    }
    return resolveOneOffline(entries, value);
  }

  private static Object resolveOneOffline(List<Map<String, String>> entries, Object value) {
    if (value instanceof String label) {
      String id = lookupOffline(entries, label);
      return id == null ? value : Map.of("id", id);
    }
    if (value instanceof Map<?, ?> map) {
      if (map.get("id") instanceof String) {
        return value;
      }
      Object label = map.get("value");
      if (label == null) {
        label = map.get("name");
      }
      if (label instanceof String text) {
        String id = lookupOffline(entries, text);
        if (id != null) {
          return Map.of("id", id);
        }
      }
    }
    return value;
  }

  private static String lookupOffline(List<Map<String, String>> entries, String label) {
    for (var entry : entries) {
      if (entry.get("value").equals(label)) {
        return entry.get("id");
      }
    }
    String wanted = label.trim();
    String found = null;
    for (var entry : entries) {
      if (entry.get("value").trim().equalsIgnoreCase(wanted)) {
        if (found != null) {
          return null;
        }
        found = entry.get("id");
      }
    }
    return found;
  }

  /** Maps a pulled {@code {id}} reference back to its bare label, when the id is known. */
  public static Object toLabel(List<Object> choices, Object value) {
    if (value instanceof Map<?, ?> map && map.get("id") instanceof String id) {
      String label = labelFor(choices, id);
      return label == null ? value : label;
    }
    if (value instanceof List<?> list) {
      // Distinct instances keep the YAML writer from emitting aliases for shared empties.
      var mapped = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof Map<?, ?> map && map.get("id") instanceof String id) {
          String label = labelFor(choices, id);
          mapped.add(label == null ? item : label);
        } else {
          mapped.add(item);
        }
      }
      return new ArrayList<>(mapped);
    }
    return value;
  }

  private static String labelFor(List<Object> choices, String id) {
    for (Object choice : choices) {
      if (choice instanceof Map<?, ?> map && id.equals(map.get("id"))) {
        Object text = map.get("value");
        if (text == null) {
          text = map.get("name");
        }
        if (text instanceof String label) {
          return label;
        }
      }
    }
    return null;
  }
}
