package dev.vspex.jira.mark.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Strict versioned profiles: unknown keys and ambiguous definitions are errors. */
public final class ProfileLoader {
  private ProfileLoader() {}

  public static Profile load(Path file) throws IOException {
    String text = Files.readString(file).replace("\r\n", "\n");
    if (text.startsWith("\uFEFF")) {
      text = text.substring(1);
    }
    String[] lines = text.split("\n", -1);
    if (lines.length == 0 || !lines[0].equals("---")) {
      throw new ProfileException(
          file.getFileName() + ": expected YAML front matter starting with ---");
    }
    int end = 1;
    while (end < lines.length && !lines[end].equals("---")) {
      end++;
    }
    if (end == lines.length) {
      throw new ProfileException(file.getFileName() + ": missing closing ---");
    }
    Map<String, Object> header = yamlMapping(String.join("\n", List.of(lines).subList(1, end)));
    onlyKeys(
        header, Set.of("schemaVersion", "profile", "project", "issueType", "fields"), "profile");
    if (!Integer.valueOf(1).equals(header.get("schemaVersion"))) {
      throw new ProfileException("schemaVersion must be 1");
    }
    String name = requiredText(header, "profile");
    if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw new ProfileException("profile must be a lowercase name such as backend-bug");
    }
    String project = requiredText(header, "project");
    String issueType = requiredText(header, "issueType");
    Map<String, Object> definitions = mapping(header.get("fields"), "fields");
    Map<String, FieldDefinition> fields = new LinkedHashMap<>();
    Set<String> ids = new HashSet<>();
    for (var entry : definitions.entrySet()) {
      String alias = entry.getKey();
      if (!alias.matches("[A-Za-z][A-Za-z0-9_]*")
          || Set.of("project", "issuetype").contains(alias.toLowerCase(java.util.Locale.ROOT))) {
        throw new ProfileException("Invalid or reserved field alias: " + alias);
      }
      Map<String, Object> config = mapping(entry.getValue(), "field " + alias);
      onlyKeys(
          config, Set.of("id", "type", "required", "default", "allowedValues"), "field " + alias);
      String id = config.containsKey("id") ? requiredText(config, "id") : alias;
      if (!id.matches("[A-Za-z][A-Za-z0-9_]*")
          || Set.of("project", "issuetype").contains(id.toLowerCase(java.util.Locale.ROOT))
          || !ids.add(id)) {
        throw new ProfileException("Invalid, reserved, or duplicate Jira field id for " + alias);
      }
      String type = config.containsKey("type") ? requiredText(config, "type") : "string";
      if (!FieldDefinition.TYPES.contains(type)) {
        throw new ProfileException("Unsupported type for field " + alias);
      }
      Object required = config.getOrDefault("required", false);
      if (!(required instanceof Boolean)) {
        throw new ProfileException("required must be a boolean for field " + alias);
      }
      Object allowed = config.getOrDefault("allowedValues", List.of());
      if (!(allowed instanceof List<?> allowedList)
          || allowedList.stream().anyMatch(java.util.Objects::isNull)) {
        throw new ProfileException("allowedValues must be a list without nulls for field " + alias);
      }
      FieldDefinition definition =
          new FieldDefinition(
              id, type, (Boolean) required, config.get("default"), new ArrayList<>(allowedList));
      for (Object value : allowedList) {
        if (!definition.accepts(value)) {
          throw new ProfileException("allowedValues has an incorrect type for field " + alias);
        }
      }
      definition.validate(definition.defaultValue(), alias);
      fields.put(alias, definition);
    }
    if (!fields.containsKey("summary")
        || !fields.get("summary").id().equals("summary")
        || !fields.get("summary").type().equals("string")
        || !fields.get("summary").required()) {
      throw new ProfileException("fields.summary must be a required string mapped to summary");
    }
    return new Profile(
        name,
        project,
        issueType,
        Collections.unmodifiableMap(fields),
        String.join("\n", List.of(lines).subList(end + 1, lines.length)).strip());
  }

  /** Loads flat alias-to-value YAML or JSON input. Only JSON-compatible values are accepted. */
  public static Map<String, Object> readInput(Path path) throws IOException {
    return yamlMapping(Files.readString(path));
  }

  private static Map<String, Object> yamlMapping(String text) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(30);
    options.setCodePointLimit(1_000_000);
    try {
      Object parsed = new Yaml(new SafeConstructor(options)).load(text);
      // Reject YAML-specific object types (dates, sets, binary) and non-finite numbers.
      checkJsonValue(parsed);
      return mapping(parsed, "document");
    } catch (YAMLException exception) {
      // YAML diagnostics can contain source snippets; do not expose field values.
      throw new ProfileException("Invalid YAML: check syntax, duplicate keys, tags, and nesting");
    }
  }

  private static void checkJsonValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String)) {
          throw new ProfileException("Mapping keys must be strings");
        }
        checkJsonValue(entry.getValue());
      }
    } else if (value instanceof List<?> list) {
      list.forEach(ProfileLoader::checkJsonValue);
    } else if (value != null
        && !(value instanceof String)
        && !(value instanceof Boolean)
        && !(value instanceof Number)) {
      throw new ProfileException("Use JSON-compatible YAML values; quote dates as strings");
    } else if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
      throw new ProfileException("Numbers must be finite");
    }
  }

  private static Map<String, Object> mapping(Object value, String context) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw new ProfileException(context + " must be a mapping");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach((key, item) -> result.put((String) key, item));
    return result;
  }

  private static String requiredText(Map<String, Object> config, String key) {
    if (!(config.get(key) instanceof String value) || value.isBlank()) {
      throw new ProfileException(key + " must be a nonempty string");
    }
    return value;
  }

  private static void onlyKeys(Map<String, Object> config, Set<String> allowed, String context) {
    for (String key : config.keySet()) {
      if (!allowed.contains(key)) {
        throw new ProfileException("Unknown key '" + key + "' in " + context);
      }
    }
  }
}
