package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.REMOTE_VALIDATION;

import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraFieldDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads Data Center text and the plain paragraph subset of Cloud ADF without flattening rich text.
 */
public final class CloudFieldDecoder implements JiraFieldDecoder {
  @Override
  public Object decode(FieldDefinition field, Object value, String alias) {
    if (value == null) return null;
    Object decoded =
        switch (field.type()) {
          case "string" -> value instanceof Map<?, ?> doc ? plainText(doc, alias) : value;
          case "object" -> reference(value, alias);
          case "array" -> {
            if (!(value instanceof List<?> values)) throw invalid(alias);
            yield values.stream().map(item -> reference(item, alias)).toList();
          }
          default -> value;
        };
    if (!field.accepts(decoded)) throw invalid(alias);
    return decoded;
  }

  private static Map<String, Object> reference(Object value, String alias) {
    if (!(value instanceof Map<?, ?> reference)
        || !(reference.get("id") instanceof String id)
        || id.isBlank()) throw invalid(alias);
    return Map.of("id", id);
  }

  private static String plainText(Map<?, ?> document, String alias) {
    if (!Set.of("type", "version", "content").containsAll(document.keySet())
        || !"doc".equals(document.get("type"))
        || !(document.get("version") instanceof Number version)
        || version.doubleValue() != 1.0
        || !(document.get("content") instanceof List<?> paragraphs)) {
      throw invalid(alias);
    }
    var lines = new ArrayList<String>();
    for (Object node : paragraphs) {
      if (!(node instanceof Map<?, ?> paragraph)
          || !Set.of("type", "content", "attrs").containsAll(paragraph.keySet())
          || !"paragraph".equals(paragraph.get("type"))
          || paragraph.get("attrs") != null && !Map.of().equals(paragraph.get("attrs")))
        throw invalid(alias);
      Object contents = paragraph.get("content");
      if (contents == null) {
        lines.add("");
        continue;
      }
      if (!(contents instanceof List<?> texts)) throw invalid(alias);
      var line = new StringBuilder();
      for (Object inline : texts) {
        if (!(inline instanceof Map<?, ?> text)
            || !Set.of("type", "text", "marks").containsAll(text.keySet())
            || !"text".equals(text.get("type"))
            || !(text.get("text") instanceof String content)
            || text.get("marks") != null && !List.of().equals(text.get("marks")))
          throw invalid(alias);
        line.append(content);
      }
      lines.add(line.toString());
    }
    return String.join("\n", lines);
  }

  private static JiraFailure invalid(String alias) {
    return new JiraFailure(
        REMOTE_VALIDATION,
        "Field '"
            + alias
            + "' cannot be converted to the profile type without losing data; the input file was not changed");
  }
}
