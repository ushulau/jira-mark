package dev.vspex.jira.mark.remote;

import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.profile.ProfileException;
import dev.vspex.jira.mark.profile.ProfileLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

/** Writes non-overwriting profile drafts outside the active profile directory. */
public final class ProfileImporter {
  private static final Set<String> REUSABLE =
      Set.of("labels", "components", "fixVersions", "versions", "priority");

  /** Options for readable alias and field reference generation. */
  public record Options(
      String aliasStyle, int maxAliasLength, int maxAllowedValues, List<String> aliasDropWords) {
    public Options {
      aliasDropWords = List.copyOf(aliasDropWords == null ? List.of() : aliasDropWords);
    }

    public static Options defaults() {
      return new Options("short", 40, 25, List.of());
    }
  }

  private final JiraGateway gateway;
  private final Options options;

  public ProfileImporter(JiraGateway gateway) {
    this(gateway, Options.defaults());
  }

  public ProfileImporter(JiraGateway gateway, Options options) {
    this.gateway = gateway;
    this.options = options == null ? Options.defaults() : options;
  }

  public Result importProfile(
      String issue, String name, Set<String> defaults, Path output, Path activeProfiles)
      throws IOException {
    if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw new ProfileException("profile must be a lowercase name such as backend-bug");
    }
    if (!Set.of("full", "short").contains(options.aliasStyle())) {
      throw new ProfileException("--alias-style must be full or short");
    }
    if (options.maxAliasLength() < 1 || options.maxAllowedValues() < 0) {
      throw new ProfileException("Alias and option limits must be non-negative");
    }
    if (!REUSABLE.containsAll(defaults)) {
      throw new ProfileException(
          "Only labels, components, versions, fixVersions, and priority may be reused");
    }
    Path destination = output.toAbsolutePath().normalize();
    Path active = realPath(activeProfiles);
    Path parent = realPath(destination.getParent());
    if (parent.startsWith(active)
        || destination.startsWith(activeProfiles.toAbsolutePath().normalize())) {
      throw new ProfileException("Import drafts must be outside the active profiles directory");
    }
    if (!destination.toString().endsWith(".md")) {
      throw new ProfileException("Import output must be a .md file");
    }
    if (Files.exists(destination)) {
      throw new ProfileException("Import output already exists");
    }
    var example = gateway.example(issue);
    var metadata = gateway.metadata(example.projectId(), example.issueTypeId());
    for (String selected : defaults) {
      var field = metadata.fields().get(selected);
      if (field == null
          || !field.supported()
          || !field.settable()
          || example.fields().get(selected) == null) {
        throw new ProfileException("Selected reusable default is unavailable: " + selected);
      }
    }
    // Deterministic readable aliases with stability for re-imports.
    Map<String, String> names = new LinkedHashMap<>();
    for (var field : metadata.fields().values()) {
      if (Set.of("project", "issuetype").contains(field.id())) {
        continue;
      }
      names.put(field.id(), field.name() != null ? field.name() : field.id());
    }
    Set<String> dropWords =
        new java.util.HashSet<>(
            options.aliasDropWords().stream().map(String::toLowerCase).toList());
    Map<String, String> aliases =
        AliasGenerator.generate(
            names, existingAliases(active, name), options.aliasStyle(), dropWords);
    var notes = new ArrayList<String>();
    notes.addAll(AliasGenerator.longAliasNotes(aliases, options.maxAliasLength()));
    Map<String, Object> fields = new LinkedHashMap<>();
    List<ProfileDocs.Row> rows = new ArrayList<>();
    for (var field : metadata.fields().values()) {
      if (Set.of("project", "issuetype").contains(field.id())) {
        continue;
      }
      String displayName = field.name() != null ? field.name() : field.id();
      String alias = aliases.get(field.id());
      if (!field.supported() || !field.settable()) {
        notes.add(
            "Unsupported field: "
                + field.id()
                + " ("
                + displayName
                + (field.custom() != null ? ", " + field.custom() : "")
                + ")");
        rows.add(
            new ProfileDocs.Row(
                displayName,
                field.id(),
                null,
                "unsupported",
                field.required(),
                List.of(),
                List.of(),
                null));
        continue;
      }
      if (field.required() && field.hasDefault()) {
        notes.add("Required field filled by Jira: " + field.id());
        rows.add(
            new ProfileDocs.Row(
                displayName, field.id(), null, "set by Jira", true, List.of(), List.of(), null));
        continue;
      }
      Map<String, Object> definition = new LinkedHashMap<>();
      if (!alias.equals(field.id())) {
        definition.put("id", field.id());
      }
      if (field.name() != null && !alias.equals(field.id())) {
        definition.put("name", field.name());
      }
      definition.put("type", field.localType());
      definition.put("required", field.required());
      List<String> labels = new ArrayList<>();
      List<String> ids = new ArrayList<>();
      Integer count = null;
      if (!field.choices().isEmpty()
          && Set.of("reference", "references").contains(field.encoding())) {
        for (Object choice : field.choices()) {
          if (choice instanceof Map<?, ?> map) {
            Object id = map.get("id");
            Object label = map.get("value");
            if (label == null) {
              label = map.get("name");
            }
            if (id instanceof String idText && label instanceof String labelText) {
              ids.add(idText);
              labels.add(labelText);
            }
          }
        }
        if (ids.size() != field.choices().size()) {
          labels.clear();
          ids.clear();
        } else if (ids.size() > options.maxAllowedValues()) {
          count = ids.size();
          labels.clear();
          ids.clear();
        }
      }
      if (count != null) {
        definition.put("allowedValuesCount", count);
      } else if (!ids.isEmpty()) {
        var allowed = new ArrayList<Object>();
        for (int i = 0; i < ids.size(); i++) {
          var entry = new LinkedHashMap<String, Object>();
          entry.put("id", ids.get(i));
          entry.put("value", labels.get(i));
          allowed.add(entry);
        }
        definition.put("allowedValues", allowed);
      }
      if (defaults.contains(field.id()) && example.fields().get(field.id()) != null) {
        definition.put("default", reusableValue(field, example.fields().get(field.id())));
      }
      fields.put(alias, definition);
      rows.add(
          new ProfileDocs.Row(
              displayName,
              field.id(),
              alias,
              displayKind(field),
              field.required(),
              count != null ? List.of() : List.copyOf(labels),
              count != null ? List.of() : List.copyOf(ids),
              count));
    }
    if (!fields.containsKey("summary")) {
      throw new ProfileException("Jira metadata does not support summary");
    }
    @SuppressWarnings("unchecked")
    var summary = (Map<String, Object>) fields.get("summary");
    summary.put("type", "string");
    summary.put("required", true);
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("schemaVersion", 1);
    header.put("profile", name);
    header.put("project", metadata.projectId());
    header.put("issueType", metadata.issueTypeId());
    header.put("fields", fields);
    var dumpOptions = new DumperOptions();
    dumpOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    dumpOptions.setAllowUnicode(true);
    dumpOptions.setSplitLines(false);
    String yaml = new Yaml(new QuotingRepresenter(dumpOptions), dumpOptions).dump(header);
    String table = ProfileDocs.renderTable(rows);
    String text =
        "---\n"
            + yaml
            + "---\n\n# "
            + name
            + "\n\nDraft imported from "
            + example.key()
            + " on "
            + LocalDate.now()
            + ". Replace this line with guidance on when to use the profile and what good values look like.\n\n"
            + ProfileDocs.BEGIN
            + "\n"
            + table.strip()
            + "\n"
            + ProfileDocs.END
            + "\n## Review notes\n"
            + (notes.isEmpty()
                ? "None.\n"
                : String.join("\n", notes.stream().map(note -> "- " + note).toList()) + "\n");
    Files.createDirectories(destination.getParent());
    Files.writeString(destination, text, StandardOpenOption.CREATE_NEW);
    try {
      ProfileLoader.load(destination);
    } catch (RuntimeException | IOException failure) {
      Files.deleteIfExists(destination);
      throw failure;
    }
    return new Result(destination, List.copyOf(notes));
  }

  static String displayKind(JiraGateway.Field field) {
    if (field.custom() != null) {
      if (field.custom().endsWith(":multicheckboxes")) {
        return "checkboxes";
      }
      if (field.custom().endsWith(":multiselect")) {
        return "multiselect";
      }
      if (field.custom().endsWith(":radiobuttons")) {
        return "radio";
      }
      if (field.custom().endsWith(":cascadingselect")) {
        return "cascading";
      }
    }
    if ("multi-version".equals(field.kind())) {
      return "versions";
    }
    return field.kind() != null ? field.kind() : "unsupported";
  }

  private static Map<String, String> existingAliases(Path active, String name) {
    Map<String, String> existing = new LinkedHashMap<>();
    try {
      if (!Files.isDirectory(active)) {
        return existing;
      }
      try (var files = Files.list(active)) {
        for (Path file :
            files
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .sorted()
                .toList()) {
          try {
            var profile = ProfileLoader.load(file);
            if (profile.name().equals(name)) {
              for (var entry : profile.fields().entrySet()) {
                existing.putIfAbsent(entry.getValue().id(), entry.getKey());
              }
            }
          } catch (RuntimeException | IOException ignored) {
            // Unreadable drafts never block a new import.
          }
        }
      }
    } catch (IOException ignored) {
      // Stability is best-effort; generation stays deterministic regardless.
    }
    return existing;
  }

  private static Object reusableValue(JiraGateway.Field field, Object value) {
    Object safe = value;
    if (value instanceof Map<?, ?> map) {
      safe = identity(map);
    } else if (value instanceof List<?> list && !field.localType().equals("string-list")) {
      safe =
          list.stream()
              .map(
                  item -> {
                    if (!(item instanceof Map<?, ?> map)) {
                      throw new ProfileException("Unsupported reusable default shape");
                    }
                    return identity(map);
                  })
              .toList();
    }
    new FieldDefinition(field.id(), field.localType(), false, null, List.of())
        .validate(safe, field.id());
    return safe;
  }

  private static Map<String, Object> identity(Map<?, ?> map) {
    Object id = map.get("id");
    if (!(id instanceof String text) || text.isBlank()) {
      throw new ProfileException("Reusable reference needs an id");
    }
    return Map.of("id", id);
  }

  private static Path realPath(Path path) throws IOException {
    Path normalized = path.toAbsolutePath().normalize();
    if (Files.exists(normalized)) {
      return normalized.toRealPath();
    }
    return realPath(normalized.getParent()).resolve(normalized.getFileName());
  }

  /** Quotes scalars that YAML 1.1 would read as booleans or numbers. */
  static final class QuotingRepresenter extends Representer {
    QuotingRepresenter(DumperOptions options) {
      super(options);
    }

    @Override
    protected Node representScalar(Tag tag, String value, DumperOptions.ScalarStyle style) {
      if (tag == Tag.STR && value != null && isRisky(value)) {
        return super.representScalar(tag, value, DumperOptions.ScalarStyle.SINGLE_QUOTED);
      }
      return super.representScalar(tag, value, style);
    }

    static boolean isRisky(String value) {
      String text = value.trim();
      if (text.isEmpty()) {
        return false;
      }
      if (Set.of(
              "y", "Y", "yes", "Yes", "YES", "n", "N", "no", "No", "NO", "true", "True", "TRUE",
              "false", "False", "FALSE", "on", "On", "ON", "off", "Off", "OFF")
          .contains(text)) {
        return true;
      }
      try {
        // eslint-disable-next-line no-unused-vars
        Double parsed = Double.valueOf(text.replace("_", ""));
        if (text.matches("[+-]?[0-9][0-9_]*([.][0-9_]*)?([eE][+-]?[0-9]+)?")
            || text.matches("[+-]?[.][0-9][0-9_]*([eE][+-]?[0-9]+)?")
            || text.matches("0[xX][0-9a-fA-F_]+")
            || text.matches("0[oO][0-7_]+")) {
          return true;
        }
      } catch (NumberFormatException ignored) {
        // Not numeric.
      }
      return false;
    }
  }

  public record Result(Path output, List<String> reviewNotes) {}
}
