package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.REMOTE_VALIDATION;

import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraFieldEncoder;
import dev.vspex.jira.mark.remote.JiraGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit small field subset; text becomes literal ADF, never an implicit template. */
public final class CloudFieldEncoder implements JiraFieldEncoder {
  @Override
  public Object encode(JiraGateway.Field field, Object value, String alias) {
    if (!field.supported()
        || !new FieldDefinition(field.id(), field.localType(), field.required(), null, List.of())
            .accepts(value)) {
      throw invalid(alias);
    }
    return switch (field.encoding()) {
      case "adf" -> document((String) value);
      case "reference" -> reference(field, value, alias);
      case "references" ->
          ((List<?>) value).stream().map(item -> reference(field, item, alias)).toList();
      case "literal" -> {
        if (!field.choices().isEmpty()) {
          List<?> values = value instanceof List<?> list ? list : List.of(value);
          if (!field.choices().containsAll(values)) {
            throw invalid(alias);
          }
        }
        yield value;
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
