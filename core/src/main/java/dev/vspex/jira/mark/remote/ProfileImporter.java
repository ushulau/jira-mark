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

/** Writes non-overwriting profile drafts outside the active profile directory. */
public final class ProfileImporter {
  private static final Set<String> REUSABLE =
      Set.of("labels", "components", "fixVersions", "versions", "priority");
  private final JiraGateway gateway;

  public ProfileImporter(JiraGateway gateway) {
    this.gateway = gateway;
  }

  public Result importProfile(
      String issue, String name, Set<String> defaults, Path output, Path activeProfiles)
      throws IOException {
    if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw new ProfileException("profile must be a lowercase name such as backend-bug");
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
    Map<String, Object> fields = new LinkedHashMap<>();
    var notes = new ArrayList<String>();
    for (var field : metadata.fields().values()) {
      if (Set.of("project", "issuetype").contains(field.id())) {
        continue;
      }
      if (!field.supported() || !field.settable()) {
        notes.add(
            "Review unsupported field: " + field.id() + (field.required() ? " (required)" : ""));
        continue;
      }
      Map<String, Object> definition = new LinkedHashMap<>();
      definition.put("type", field.localType());
      definition.put("required", field.required());
      if (defaults.contains(field.id()) && example.fields().get(field.id()) != null) {
        definition.put("default", reusableValue(field, example.fields().get(field.id())));
      }
      fields.put(field.id(), definition);
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
    var options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    String text =
        "---\n"
            + new Yaml(options).dump(header)
            + "---\n\n# "
            + name
            + "\n\nDraft imported from "
            + example.key()
            + " on "
            + LocalDate.now()
            + ". Review field aliases and required fields before activation.\n\n"
            + "Describe when to use this profile and what good ticket values look like.\n"
            + String.join("\n", notes.stream().map(note -> "\n- " + note).toList())
            + "\n";
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

  public record Result(Path output, List<String> reviewNotes) {}
}
