package dev.vspex.jira.mark.remote;

import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.jira.mark.profile.Profile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileSyncTest {
  static JiraGateway.Field field(
      String id, String name, String kind, String local, boolean required, String encoding) {
    return new JiraGateway.Field(
        id, name, kind, local, required, false, true, encoding, List.of(), null);
  }

  static JiraGateway.Field options(String id, String name, boolean required, List<Object> choices) {
    return new JiraGateway.Field(
        id, name, "select", "object", required, false, true, "reference", choices, null);
  }

  @Test
  void reportsAddedRemovedChangedAndUnchanged() {
    var fields = new LinkedHashMap<String, dev.vspex.jira.mark.profile.FieldDefinition>();
    fields.put(
        "summary",
        new dev.vspex.jira.mark.profile.FieldDefinition(
            "summary", "string", true, null, List.of()));
    fields.put(
        "changeUrgency",
        new dev.vspex.jira.mark.profile.FieldDefinition(
            "customfield_10302",
            "object",
            false,
            null,
            List.of(Map.of("id", "10400", "value", "Normal - Planned"))));
    fields.put(
        "legacy",
        new dev.vspex.jira.mark.profile.FieldDefinition(
            "customfield_10318", "string", false, null, List.of()));
    var profile = new Profile("change-request", "100", "17", fields, "Guidance");
    var metadata =
        new JiraGateway.Metadata(
            "100",
            "17",
            Map.of(
                "summary", field("summary", "Summary", "text", "string", true, "literal"),
                "customfield_10302",
                    options(
                        "customfield_10302",
                        "Change Urgency",
                        true,
                        List.of(
                            Map.of("id", "10400", "value", "Normal - Planned"),
                            Map.of("id", "10402", "value", "Unplanned"))),
                "customfield_20001",
                    field(
                        "customfield_20001",
                        "Rollback Owner",
                        "user",
                        "object",
                        false,
                        "user-dc")));
    var drift = ProfileSync.compare(profile, metadata);
    assertEquals(1, drift.added().size());
    assertEquals("customfield_20001", drift.added().get(0).fieldId());
    assertEquals(1, drift.removed().size());
    assertEquals("customfield_10318", drift.removed().get(0).fieldId());
    assertEquals(1, drift.changed().size());
    assertTrue(drift.changed().get(0).detail().contains("required"));
    assertTrue(drift.changed().get(0).detail().contains("+10402"));
    assertEquals(1, drift.unchanged());
    assertTrue(drift.hasDrift());
    String report = ProfileSync.formatReport("change-request", "100", "17", drift);
    assertTrue(report.contains("+ customfield_20001"));
    assertTrue(report.contains("- customfield_10318"));
    assertTrue(report.contains("~ customfield_10302"));
    assertTrue(report.contains("Drift: yes"));
  }

  @Test
  void rewriteDocsPreservesFrontMatterAndBodyText() {
    var fields = new LinkedHashMap<String, dev.vspex.jira.mark.profile.FieldDefinition>();
    fields.put(
        "summary",
        new dev.vspex.jira.mark.profile.FieldDefinition(
            "summary", "string", true, null, List.of()));
    var profile = new Profile("bug", "APP", "Bug", fields, "Hand-written guidance.");
    var metadata =
        new JiraGateway.Metadata(
            "100",
            "2",
            Map.of("summary", field("summary", "Summary", "text", "string", true, "literal")));
    String file =
        "---\n"
            + "schemaVersion: 1\nprofile: bug\nproject: APP\nissueType: Bug\nfields:\n  summary:\n    required: true\n---\n\n# bug\n\nHand-written guidance.\n";
    String updated = ProfileSync.rewriteDocs(file, profile, metadata, 25);
    assertTrue(updated.startsWith("---\n"));
    assertTrue(updated.contains("schemaVersion: 1"));
    assertTrue(updated.contains("Hand-written guidance."));
    assertTrue(updated.contains("<!-- jira-mark:fields:begin -->"));
    assertTrue(updated.contains("| Summary |"));
    // A second rewrite only refreshes the marker section.
    assertEquals(updated, ProfileSync.rewriteDocs(updated, profile, metadata, 25));
  }
}
