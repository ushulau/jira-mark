package dev.vspex.jira.mark.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileWorkflowTest {
  @TempDir Path directory;

  private static final String HEADER =
      """
      ---
      schemaVersion: 1
      profile: backend-bug
      project: APP
      issueType: Bug
      fields:
        summary:
          type: string
          required: true
        labels:
          type: string-list
          default: [backend]
        criteria:
          id: customfield_10042
          type: string
          required: true
      ---
      # Guidance
      Explain the failure.
      """;

  private Profile load(String text) throws Exception {
    Path file = directory.resolve("bug.md");
    Files.writeString(file, text);
    return ProfileLoader.load(file);
  }

  @Test
  void resolvesDefaultsFileAndArgumentsAndMapsCustomFields() throws Exception {
    Profile profile = load(HEADER);
    var draft =
        DraftResolver.resolve(
            profile,
            Map.of("summary", "From file", "criteria", "Testable"),
            List.of("summary=CLI=value"));
    var fields = (Map<?, ?>) draft.get("fields");
    assertEquals("CLI=value", fields.get("summary"));
    assertEquals("Testable", fields.get("customfield_10042"));
    assertEquals(List.of("backend"), fields.get("labels"));
    assertTrue(profile.guidance().contains("Explain the failure"));
    assertFalse(fields.containsValue(profile.guidance()));
  }

  @Test
  void reportsAllMissingRequiredFieldsWithoutPrompting() throws Exception {
    Profile profile = load(HEADER);
    var failure =
        assertThrows(
            ProfileException.class, () -> DraftResolver.resolve(profile, Map.of(), List.of()));
    assertEquals("Missing required fields: summary, criteria", failure.getMessage());
  }

  @Test
  void rejectsUnknownInputsAndWrongTypes() throws Exception {
    Profile profile = load(HEADER);
    assertThrows(
        ProfileException.class,
        () -> DraftResolver.resolve(profile, Map.of("summry", "Secret"), List.of()));
    var failure =
        assertThrows(
            ProfileException.class,
            () -> DraftResolver.resolve(profile, Map.of("labels", "Secret"), List.of()));
    assertFalse(failure.getMessage().contains("Secret"));
  }

  @Test
  void rejectsDuplicateYamlKeysAndUnknownConfiguration() {
    assertThrows(
        ProfileException.class,
        () -> load(HEADER.replace("project: APP", "project: APP\nproject: OTHER")));
    assertThrows(
        ProfileException.class, () -> load(HEADER.replace("required: true", "requried: true")));
  }

  @Test
  void rejectsDuplicateAndReservedFieldMappings() {
    assertThrows(
        ProfileException.class, () -> load(HEADER.replace("id: customfield_10042", "id: summary")));
    assertThrows(
        ProfileException.class, () -> load(HEADER.replace("id: customfield_10042", "id: project")));
  }

  @Test
  void validatesDefaultAndAllowedValueTypes() {
    assertThrows(
        ProfileException.class,
        () -> load(HEADER.replace("default: [backend]", "default: backend")));
    assertThrows(
        ProfileException.class,
        () -> load(HEADER.replace("default: [backend]", "allowedValues: [backend]")));
    assertThrows(
        ProfileException.class,
        () ->
            load(
                HEADER.replace(
                    "type: string\n    required: true",
                    "type: string\n    required: true\n    default: x\n    allowedValues: [y]")));
  }

  @Test
  void requiresExplicitUniqueProfileSelection() throws Exception {
    load(HEADER);
    assertThrows(ProfileException.class, () -> ProfileRepository.select(directory, "Bug"));
    Files.writeString(directory.resolve("duplicate.md"), HEADER);
    assertThrows(ProfileException.class, () -> ProfileRepository.load(directory));
  }

  @Test
  void supportsBomAndWindowsLineEndings() throws Exception {
    assertEquals("backend-bug", load("\uFEFF" + HEADER.replace("\n", "\r\n")).name());
  }

  @Test
  void rejectsTagsAliasesAndNonJsonValues() {
    assertThrows(
        ProfileException.class,
        () ->
            load(
                HEADER.replace(
                    "default: [backend]", "default: !!java.net.URL ['https://example.com']")));
    assertThrows(
        ProfileException.class,
        () ->
            load(
                HEADER.replace(
                    "default: [backend]", "default: &a [backend]\n    allowedValues: [*a]")));
    assertThrows(
        ProfileException.class,
        () -> load(HEADER.replace("default: [backend]", "default: 2026-10-18")));
  }

  @Test
  void readsJsonAndYamlFieldInputAndPreservesFalseAndZero() throws Exception {
    Path input = directory.resolve("input.json");
    Files.writeString(input, "{\"summary\":\"Test\",\"enabled\":false,\"count\":0}");
    assertEquals(false, ProfileLoader.readInput(input).get("enabled"));
    var bool = new FieldDefinition("enabled", "boolean", true, null, List.of());
    var count = new FieldDefinition("count", "integer", true, null, List.of());
    var summary = new FieldDefinition("summary", "string", true, null, List.of());
    var profile =
        new Profile(
            "test", "APP", "Task", Map.of("summary", summary, "enabled", bool, "count", count), "");
    assertDoesNotThrow(
        () -> DraftResolver.resolve(profile, ProfileLoader.readInput(input), List.of()));
    Files.writeString(input, "summary: Test\nenabled: false\ncount: 0\n");
    assertEquals(0, ProfileLoader.readInput(input).get("count"));
  }

  @Test
  void parsesTypedArgumentsAndRejectsInvalidBoolean() {
    var definition = new FieldDefinition("enabled", "boolean", false, null, List.of());
    assertEquals(false, definition.parseArgument("false", "enabled"));
    assertThrows(ProfileException.class, () -> definition.parseArgument("yes", "enabled"));
    var labels = new FieldDefinition("labels", "string-list", false, null, List.of());
    assertEquals(List.of("one", "two"), labels.parseArgument("[\"one\",\"two\"]", "labels"));
  }

  @Test
  void explicitNullClearsDefaultsAndEmptyRequiredValuesDoNotFallBack() throws Exception {
    Profile profile = load(HEADER);
    var input = new java.util.LinkedHashMap<String, Object>();
    input.put("summary", "Synthetic");
    input.put("criteria", "Testable");
    input.put("labels", null);
    var fields = (Map<?, ?>) DraftResolver.resolve(profile, input, List.of()).get("fields");
    assertFalse(fields.containsKey("labels"));
    input.put("summary", "");
    assertThrows(ProfileException.class, () -> DraftResolver.resolve(profile, input, List.of()));
    input.put("summary", null);
    assertThrows(ProfileException.class, () -> DraftResolver.resolve(profile, input, List.of()));
  }
}
