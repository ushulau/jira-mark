package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.REMOTE_VALIDATION;

import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.remote.Datetimes;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraFieldEncoder;
import dev.vspex.jira.mark.remote.JiraGateway;
import dev.vspex.jira.mark.remote.OptionLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Encodes the supported field subset according to the deployment-specific metadata encoding. */
public final class CloudFieldEncoder implements JiraFieldEncoder {
  @Override
  public Object encode(JiraGateway.Field field, Object value, String alias) {
    if (!field.supported()
        || !new FieldDefinition(field.id(), field.localType(), field.required(), null, List.of())
            .accepts(value)) {
      throw invalid(alias);
    }
    return switch (field.encoding()) {
      case "adf" -> {
        if (!(value instanceof String text)) {
          throw invalid(alias);
        }
        yield document(text);
      }
      case "reference" -> {
        Object resolved = OptionLabels.resolveLive(field, value, alias);
        yield reference(field, resolved, alias);
      }
      case "references" -> {
        Object resolved = OptionLabels.resolveLive(field, value, alias);
        if (!(resolved instanceof List<?> list)) {
          throw invalid(alias);
        }
        yield list.stream().map(item -> reference(field, item, alias)).toList();
      }
      case "literal" -> {
        if (!field.choices().isEmpty()) {
          List<?> values = value instanceof List<?> list ? list : List.of(value);
          if (!field.choices().containsAll(values)) {
            throw invalid(alias);
          }
        }
        yield value;
      }
      case "date" -> {
        if (!(value instanceof String text)) {
          throw invalid(alias);
        }
        yield Datetimes.encodeDate(text, alias);
      }
      case "datetime" -> {
        if (!(value instanceof String text)) {
          throw invalid(alias);
        }
        yield Datetimes.encodeDatetime(text, alias);
      }
      case "user-dc" -> Datetimes.encodeUser(value, false, alias);
      case "user-cloud" -> Datetimes.encodeUser(value, true, alias);
      case "users-dc" -> Datetimes.encodeUsers(value, false, alias);
      case "users-cloud" -> Datetimes.encodeUsers(value, true, alias);
      case "cascading" -> cascading(value, alias);
      case "epic-link" -> {
        if (!(value instanceof String text) || text.isBlank()) {
          throw invalid(alias);
        }
        yield text.trim();
      }
      default -> throw invalid(alias);
    };
  }

  private static Object reference(JiraGateway.Field field, Object value, String alias) {
    if (!(value instanceof Map<?, ?> map) || map.size() != 1) {
      throw invalid(alias);
    }
    String selector =
        List.of("id", "name", "value", "key").stream()
            .filter(map::containsKey)
            .findFirst()
            .orElseThrow(() -> invalid(alias));
    Object selected = map.get(selector);
    if (!(selected instanceof String text) || text.isBlank()) {
      throw invalid(alias);
    }
    if (field.choices().isEmpty()) {
      if (!selector.equals("id")) {
        throw new JiraFailure(
            REMOTE_VALIDATION,
            "Field '" + alias + "' needs an explicit id; Jira returned no choices");
      }
      return Map.of("id", text);
    }
    var matches =
        field.choices().stream()
            .filter(
                item -> item instanceof Map<?, ?> choice && selected.equals(choice.get(selector)))
            .toList();
    if (matches.size() != 1 || !(((Map<?, ?>) matches.get(0)).get("id") instanceof String id)) {
      throw invalid(alias);
    }
    return Map.of("id", id);
  }

  private static Object cascading(Object value, String alias) {
    if (value instanceof String text) {
      String[] parts = text.split("/", 2);
      String parent = parts[0].trim();
      if (parent.isEmpty()) {
        throw invalid(alias);
      }
      if (parts.length == 1) {
        return Map.of("value", parent);
      }
      String child = parts[1].trim();
      if (child.isEmpty()) {
        return Map.of("value", parent);
      }
      return Map.of("value", parent, "child", Map.of("value", child));
    }
    if (value instanceof Map<?, ?> map && !map.isEmpty()) {
      // Accept id- or value-shaped cascading maps as given; Jira validates the option path.
      var copy = new LinkedHashMap<String, Object>();
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalid(alias);
        }
        copy.put(key, entry.getValue());
      }
      if (!copy.containsKey("id") && !copy.containsKey("value")) {
        throw invalid(alias);
      }
      return Map.copyOf(copy);
    }
    throw invalid(alias);
  }

  private static Map<String, Object> document(String text) {
    var paragraphs = new ArrayList<Object>();
    for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
      paragraphs.add(
          line.isEmpty()
              ? Map.of("type", "paragraph")
              : Map.of(
                  "type", "paragraph", "content", List.of(Map.of("type", "text", "text", line))));
    }
    return Map.of("version", 1, "type", "doc", "content", paragraphs);
  }

  private static JiraFailure invalid(String alias) {
    return new JiraFailure(
        REMOTE_VALIDATION, "Field '" + alias + "' has an unsupported shape or unavailable choice");
  }
}
