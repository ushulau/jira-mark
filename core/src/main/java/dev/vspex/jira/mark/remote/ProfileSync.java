package dev.vspex.jira.mark.remote;

import dev.vspex.jira.mark.profile.Profile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only drift check between a profile and current Jira create metadata. Rewriting docs only
 * replaces the generated marker section, never the front matter.
 */
public final class ProfileSync {
  private ProfileSync() {}

  public record Entry(String fieldId, String name, String kind, String detail) {}

  public record Drift(List<Entry> added, List<Entry> removed, List<Entry> changed, int unchanged) {
    public boolean hasDrift() {
      return !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
    }
  }

  /** Compares profile field ids against fresh create metadata. */
  public static Drift compare(Profile profile, JiraGateway.Metadata metadata) {
    Map<String, String> profileById = new LinkedHashMap<>();
    Map<String, String> aliasById = new LinkedHashMap<>();
    for (var entry : profile.fields().entrySet()) {
      profileById.putIfAbsent(entry.getValue().id(), entry.getValue().type());
      aliasById.putIfAbsent(entry.getValue().id(), entry.getKey());
    }
    var added = new ArrayList<Entry>();
    var removed = new ArrayList<Entry>();
    var changed = new ArrayList<Entry>();
    int unchanged = 0;
    for (var field : metadata.fields().values()) {
      if (field.id().equals("project") || field.id().equals("issuetype")) {
        continue;
      }
      if (!field.supported() || !field.settable()) {
        continue;
      }
      if (field.required() && field.hasDefault()) {
        // Filled by Jira; profiles intentionally omit these.
        continue;
      }
      String profileType = profileById.get(field.id());
      if (profileType == null) {
        added.add(
            new Entry(
                field.id(),
                field.name() != null ? field.name() : field.id(),
                field.kind() != null ? field.kind() : "unsupported",
                "not in profile"));
        continue;
      }
      List<String> details = new ArrayList<>();
      if (!profileType.equals(field.localType())) {
        details.add("type: " + profileType + " -> " + field.localType());
      }
      var definition = profile.fields().get(aliasById.get(field.id()));
      boolean profileRequired = definition != null && definition.required();
      if (profileRequired != field.required()) {
        details.add("required: " + profileRequired + " -> " + field.required());
      }
      String options = optionDrift(definition, field);
      if (options != null) {
        details.add(options);
      }
      if (details.isEmpty()) {
        unchanged++;
      } else {
        changed.add(
            new Entry(
                field.id(),
                field.name() != null ? field.name() : field.id(),
                field.kind() != null ? field.kind() : "",
                String.join("; ", details)));
      }
    }
    for (var entry : profile.fields().entrySet()) {
      String id = entry.getValue().id();
      if (id.equals("summary") && entry.getKey().equals("summary")) {
        continue;
      }
      var remote = metadata.fields().get(id);
      if (remote == null || !remote.supported() || !remote.settable()) {
        String name = entry.getValue().name() != null ? entry.getValue().name() : entry.getKey();
        removed.add(new Entry(id, name, "", "in profile, no longer on create screen"));
      }
    }
    return new Drift(List.copyOf(added), List.copyOf(removed), List.copyOf(changed), unchanged);
  }

  private static String optionDrift(
      dev.vspex.jira.mark.profile.FieldDefinition definition, JiraGateway.Field field) {
    if (definition == null
        || field.choices().isEmpty()
        || !(field.encoding().equals("reference") || field.encoding().equals("references"))) {
      return null;
    }
    Map<String, String> live = new LinkedHashMap<>();
    for (Object choice : field.choices()) {
      if (choice instanceof Map<?, ?> map
          && map.get("id") instanceof String id
          && (map.get("value") instanceof String || map.get("name") instanceof String)) {
        Object label = map.get("value");
        if (label == null) {
          label = map.get("name");
        }
        live.put(id, (String) label);
      }
    }
    if (live.isEmpty()) {
      return null;
    }
    Map<String, String> profiled = new LinkedHashMap<>();
    for (var entry : definition.optionEntries()) {
      profiled.put(entry.get("id"), entry.get("value"));
    }
    if (profiled.isEmpty()) {
      return null;
    }
    var parts = new ArrayList<String>();
    for (var entry : live.entrySet()) {
      if (!profiled.containsKey(entry.getKey())) {
        parts.add("+" + entry.getKey() + " '" + entry.getValue() + "'");
      }
    }
    for (var entry : profiled.entrySet()) {
      if (!live.containsKey(entry.getKey())) {
        parts.add("-" + entry.getKey() + " '" + entry.getValue() + "'");
      }
    }
    if (parts.isEmpty()) {
      return null;
    }
    return "allowed values: " + String.join(", ", parts);
  }

  /** Formats the drift report shown on stdout. */
  public static String formatReport(
      String profileName, String project, String issueType, Drift drift) {
    var out = new StringBuilder();
    out.append("Profile ")
        .append(profileName)
        .append(" vs ")
        .append(project)
        .append(" / ")
        .append(issueType)
        .append(" (createmeta)\n");
    for (Entry entry : drift.added()) {
      out.append("  + ").append(entry.fieldId()).append("  ").append(entry.name());
      if (!entry.kind().isBlank()) {
        out.append("  ").append(entry.kind());
      }
      out.append("  ").append(entry.detail()).append("\n");
    }
    for (Entry entry : drift.removed()) {
      out.append("  - ").append(entry.fieldId()).append("  ").append(entry.name());
      out.append("  ").append(entry.detail()).append("\n");
    }
    for (Entry entry : drift.changed()) {
      out.append("  ~ ").append(entry.fieldId()).append("  ").append(entry.name());
      if (!entry.kind().isBlank()) {
        out.append("  ").append(entry.kind());
      }
      out.append("  ").append(entry.detail()).append("\n");
    }
    out.append("  = ").append(drift.unchanged()).append(" fields unchanged\n");
    out.append("Drift: ").append(drift.hasDrift() ? "yes" : "no").append("\n");
    return out.toString();
  }

  /**
   * Rebuilds the generated docs table from current metadata, reusing profile aliases for known
   * field ids. Returns the new file text with front matter untouched.
   */
  public static String rewriteDocs(
      String fileText, Profile profile, JiraGateway.Metadata metadata, int maxAllowedValues) {
    String normalized = fileText.replace("\r\n", "\n");
    String[] lines = normalized.split("\n", -1);
    int end = -1;
    if (lines.length > 0 && lines[0].equals("---")) {
      end = 1;
      while (end < lines.length && !lines[end].equals("---")) {
        end++;
      }
    }
    String front = end >= 0 ? String.join("\n", java.util.List.of(lines).subList(0, end + 1)) : "";
    String body =
        end >= 0
            ? String.join("\n", java.util.List.of(lines).subList(end + 1, lines.length))
            : normalized;
    Map<String, String> aliasById = new LinkedHashMap<>();
    for (var entry : profile.fields().entrySet()) {
      aliasById.putIfAbsent(entry.getValue().id(), entry.getKey());
    }
    List<ProfileDocs.Row> rows = new ArrayList<>();
    for (var field : metadata.fields().values()) {
      if (field.id().equals("project") || field.id().equals("issuetype")) {
        continue;
      }
      String displayName = field.name() != null ? field.name() : field.id();
      if (!field.supported() || !field.settable()) {
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
        rows.add(
            new ProfileDocs.Row(
                displayName, field.id(), null, "set by Jira", true, List.of(), List.of(), null));
        continue;
      }
      String alias = aliasById.get(field.id());
      List<String> labels = new ArrayList<>();
      List<String> ids = new ArrayList<>();
      Integer count = null;
      if (!field.choices().isEmpty()
          && (field.encoding().equals("reference") || field.encoding().equals("references"))) {
        for (Object choice : field.choices()) {
          if (choice instanceof Map<?, ?> map
              && map.get("id") instanceof String id
              && (map.get("value") instanceof String || map.get("name") instanceof String)) {
            Object label = map.get("value");
            if (label == null) {
              label = map.get("name");
            }
            ids.add(id);
            labels.add((String) label);
          }
        }
        if (ids.size() > maxAllowedValues) {
          count = ids.size();
          labels.clear();
          ids.clear();
        }
      }
      rows.add(
          new ProfileDocs.Row(
              displayName,
              field.id(),
              alias,
              field.kind() != null ? field.kind() : "unsupported",
              field.required(),
              List.copyOf(labels),
              List.copyOf(ids),
              count));
    }
    String updated = ProfileDocs.upsertSection(body, ProfileDocs.renderTable(rows));
    return front + "\n" + updated;
  }
}
