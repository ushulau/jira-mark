package dev.vspex.jira.mark.remote;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.profile.Profile;
import dev.vspex.jira.mark.profile.ProfileException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomFieldsTest {
  @Test
  void kindRegistryClassifiesPlanKinds() {
    String prefix = "com.atlassian.jira.plugin.system.customfieldtypes:";
    assertEquals(FieldKind.TEXT, FieldKind.classify(prefix + "textfield", "string", null));
    assertEquals(FieldKind.TEXT, FieldKind.classify(prefix + "textarea", "string", null));
    assertEquals(FieldKind.SELECT, FieldKind.classify(prefix + "select", "option", null));
    assertEquals(FieldKind.SELECT, FieldKind.classify(prefix + "radiobuttons", "option", null));
    assertEquals(
        FieldKind.MULTISELECT, FieldKind.classify(prefix + "multicheckboxes", "array", "option"));
    assertEquals(
        FieldKind.MULTISELECT, FieldKind.classify(prefix + "multiselect", "array", "option"));
    assertEquals(
        FieldKind.CASCADING, FieldKind.classify(prefix + "cascadingselect", "option", null));
    assertEquals(FieldKind.DATE, FieldKind.classify(prefix + "datepicker", "date", null));
    assertEquals(FieldKind.DATETIME, FieldKind.classify(prefix + "datetime", "datetime", null));
    assertEquals(FieldKind.USER, FieldKind.classify(prefix + "userpicker", "user", null));
    assertEquals(FieldKind.USER, FieldKind.classify(null, "user", null));
    assertEquals(
        FieldKind.MULTI_USER, FieldKind.classify(prefix + "multiuserpicker", "array", "user"));
    assertEquals(FieldKind.LABELS, FieldKind.classify(prefix + "labels", "array", "string"));
    assertEquals(
        FieldKind.EPIC_LINK,
        FieldKind.classify("com.pyxis.greenhopper.jira:gh-epic-link", "string", null));
    assertEquals(
        FieldKind.UNSUPPORTED,
        FieldKind.classify("com.example:unknown", "array", "string".replace("string", "weird")));
    // Unknown custom types are never guessed, even with a familiar schema shape (Sprint).
    assertEquals(
        FieldKind.UNSUPPORTED,
        FieldKind.classify("com.pyxis.greenhopper.jira:gh-sprint", "array", "string"));
    assertEquals("string", FieldKind.DATE.profileType());
    assertEquals("object", FieldKind.USER.profileType());
    assertNull(FieldKind.UNSUPPORTED.profileType());
  }

  @Test
  void datetimeFormatsWithMillisAndRejectsMissingOffset() {
    assertEquals(
        "2026-11-04T06:00:00.000-0600",
        Datetimes.encodeDatetime("2026-11-04T06:00:00-06:00", "plannedStartDateTime"));
    assertThrows(
        JiraFailure.class,
        () -> Datetimes.encodeDatetime("2026-11-04T06:00:00", "plannedStartDateTime"));
    assertEquals("2026-11-04", Datetimes.encodeDate("2026-11-04", "due"));
    assertThrows(JiraFailure.class, () -> Datetimes.encodeDate("11/04/2026", "due"));
  }

  @Test
  void userEncodingUsesNameOnDcAndAccountIdOnCloud() {
    assertEquals(Map.of("name", "jdoe"), Datetimes.encodeUser("jdoe", false, "owner"));
    assertEquals(
        Map.of("accountId", "557058:f7d1a2b3-4c5d-6e7f-8090-a1b2c3d4e5f6"),
        Datetimes.encodeUser("557058:f7d1a2b3-4c5d-6e7f-8090-a1b2c3d4e5f6", true, "owner"));
    assertEquals(
        INVALID_INPUT,
        assertThrows(JiraFailure.class, () -> Datetimes.encodeUser("jdoe", true, "owner")).code());
  }

  @Test
  void optionLabelsResolveExactlyThenCaseInsensitively() {
    var field =
        new JiraGateway.Field(
            "customfield_10302",
            "object",
            false,
            false,
            true,
            "reference",
            List.of(
                Map.of("id", "10400", "value", "Normal - Planned"),
                Map.of("id", "10401", "value", "Emergency")));
    assertEquals(
        Map.of("id", "10400"),
        OptionLabels.resolveLive(field, "Normal - Planned", "changeUrgency"));
    assertEquals(
        Map.of("id", "10400"),
        OptionLabels.resolveLive(field, "normal - planned", "changeUrgency"));
    assertEquals(
        Map.of("id", "10400"),
        OptionLabels.resolveLive(field, Map.of("value", "Normal - Planned"), "changeUrgency"));
    var failure =
        assertThrows(
            JiraFailure.class, () -> OptionLabels.resolveLive(field, "Urgent", "changeUrgency"));
    assertEquals(INVALID_INPUT, failure.code());
    assertTrue(failure.getMessage().contains("Normal - Planned"));
    assertTrue(failure.getMessage().contains("Emergency"));
  }

  @Test
  void checkboxesResolveMixedIdsAndLabels() {
    var field =
        new JiraGateway.Field(
            "customfield_10325",
            "array",
            false,
            false,
            true,
            "references",
            List.of(
                Map.of("id", "10443", "value", "Production"),
                Map.of("id", "10452", "value", "QA")));
    assertEquals(
        List.of(Map.of("id", "10443"), Map.of("id", "10452")),
        OptionLabels.resolveLive(field, List.of("Production", Map.of("id", "10452")), "env"));
  }

  @Test
  void clearSendsNullOrEmptyListOnUpdateAndIsRejectedOnCreate() throws Exception {
    var fields = new LinkedHashMap<String, FieldDefinition>();
    fields.put("summary", new FieldDefinition("summary", "string", true, null, List.of()));
    fields.put("notes", new FieldDefinition("customfield_10322", "string", false, null, List.of()));
    fields.put("env", new FieldDefinition("customfield_10325", "array", false, null, List.of()));
    var profile = new Profile("change-request", "100", "17", fields, "");
    var gateway = new ClearingGateway();
    var workflow = new JiraWorkflow(gateway, (field, value, alias) -> value);
    var payload =
        workflow.validateUpdate(
            profile,
            Map.of(
                "summary", "Rotate TLS certificate on gateway", "$clear", List.of("notes", "env")),
            List.of(),
            "OPS-1",
            "100",
            "17");
    var sent = (Map<?, ?>) payload.get("fields");
    assertTrue(sent.containsKey("customfield_10322"));
    assertNull(sent.get("customfield_10322"));
    assertEquals(List.of(), sent.get("customfield_10325"));
    assertThrows(
        ProfileException.class,
        () ->
            workflow.validate(
                profile, Map.of("summary", "New", "$clear", List.of("notes")), List.of()));
  }

  @Test
  void clearRejectsRequiredFieldsAndDollarAliases() {
    var fields = new LinkedHashMap<String, FieldDefinition>();
    fields.put("summary", new FieldDefinition("summary", "string", true, null, List.of()));
    var profile = new Profile("bug", "APP", "Bug", fields, "");
    assertThrows(
        ProfileException.class,
        () ->
            dev.vspex.jira.mark.profile.DraftResolver.resolve(
                profile, Map.of("summary", "x", "$clear", List.of("summary")), List.of()));
    assertThrows(
        ProfileException.class,
        () ->
            dev.vspex.jira.mark.profile.DraftResolver.resolve(
                profile, Map.of("$bad", "x"), List.of()));
  }

  static class ClearingGateway implements JiraGateway {
    public Metadata metadata(String project, String issueType) {
      return editMetadata("OPS-1");
    }

    public Metadata editMetadata(String key) {
      return new Metadata(
          "100",
          "17",
          Map.of(
              "summary", new Field("summary", "string", true, false, true, "literal", List.of()),
              "customfield_10322",
                  new Field(
                      "customfield_10322", "string", false, false, true, "literal", List.of()),
              "customfield_10325",
                  new Field(
                      "customfield_10325", "array", false, false, true, "references", List.of())));
    }

    public ExampleIssue example(String key) {
      throw new AssertionError();
    }

    public CreatedIssue create(Map<String, Object> payload) {
      return new CreatedIssue("OPS-2", URI.create("https://example.invalid/browse/OPS-2"));
    }
  }
}
