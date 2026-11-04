package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.REMOTE_VALIDATION;

import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.remote.Datetimes;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraFieldDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads Data Center text and the plain paragraph subset of Cloud ADF without flattening rich text.
 * User references decode to their local shorthand; option references stay {@code {id}} unless the
 * pull requests labels.
 */
public final class CloudFieldDecoder implements JiraFieldDecoder {
  private static final Pattern DATETIME_HINT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T");

  @Override
  public Object decode(FieldDefinition field, Object value, String alias) {
    if (value == null) {
      return null;
    }
    Object decoded =
        switch (field.type()) {
          case "string" -> decodeString(value, alias);
          case "object" -> decodeObject(value, alias);
          case "array" -> {
            if (!(value instanceof List<?> values)) {
              throw invalid(alias);
            }
            // A distinct instance per field: shared empty-list singletons would make the YAML
            // writer emit aliases, which the secure loader rejects.
            var items = new ArrayList<>();
            for (Object item : values) {
              items.add(decodeObject(item, alias));
            }
            yield new ArrayList<>(items);
          }
          default -> value;
        };
    if (!field.accepts(decoded)) {
      throw invalid(alias);
    }
    return decoded;
  }

  private static Object decodeString(Object value, String alias) {
    if (value instanceof Map<?, ?> doc) {
      return plainText(doc, alias);
    }
    if (value instanceof String text && DATETIME_HINT.matcher(text).find()) {
      return Datetimes.decodeDatetime(text, alias);
    }
    return value;
  }

  static Object decodeObject(Object value, String alias) {
    if (!(value instanceof Map<?, ?> reference)) {
      return value;
    }
    if (reference.containsKey("accountId")
        || (reference.containsKey("name")
            && (reference.containsKey("key") || reference.containsKey("emailAddress")))
        || (reference.containsKey("name") && !reference.containsKey("id"))) {
      return Datetimes.decodeUser(value, reference.containsKey("accountId"), alias);
    }
    if (reference.containsKey("child")
        || (reference.containsKey("value") && reference.size() > 1)) {
      return cascading(reference, alias);
    }
    if (reference.get("id") instanceof String id && !id.isBlank()) {
      return Map.of("id", id);
    }
    if (reference.get("name") instanceof String name && !name.isBlank()
        || reference.get("value") instanceof String) {
      // Named references without an id (versions, priorities in some responses).
      return Map.of(
          "id", reference.containsKey("name") ? reference.get("name") : reference.get("value"));
    }
    return value;
  }

  private static Map<String, Object> cascading(Map<?, ?> reference, String alias) {
    var copy = new LinkedHashMap<String, Object>();
    if (reference.get("id") instanceof String id && !id.isBlank()) {
      copy.put("id", id);
    } else if (reference.get("value") instanceof String parent && !parent.isBlank()) {
      copy.put("value", parent);
    } else {
      throw invalid(alias);
    }
    Object child = reference.get("child");
    if (child instanceof Map<?, ?> childMap) {
      if (childMap.get("id") instanceof String childId && !childId.isBlank()) {
        copy.put("child", Map.of("id", childId));
      } else if (childMap.get("value") instanceof String childValue && !childValue.isBlank()) {
        copy.put("child", Map.of("value", childValue));
      }
    }
    return Map.copyOf(copy);
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
