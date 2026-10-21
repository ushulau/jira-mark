package dev.vspex.jira.mark.remote;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.profile.Profile;
import dev.vspex.jira.mark.profile.ProfileException;
import dev.vspex.jira.mark.profile.ProfileLoader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TicketPullerTest {
  @TempDir Path directory;
  Path profiles;
  Path history;
  Path input;
  Profile profile;
  FakeGateway gateway = new FakeGateway();
  TicketPuller puller = new TicketPuller(gateway, (field, value, alias) -> value);

  @BeforeEach
  void prepare() throws Exception {
    profiles = Files.createDirectories(directory.resolve("profiles"));
    history = directory.resolve("history");
    input = directory.resolve("ticket.json");
    Files.writeString(input, "{\"summary\":\"Local\",\"details\":\"Local details\"}\n");
    var fields = new LinkedHashMap<String, FieldDefinition>();
    fields.put("summary", new FieldDefinition("summary", "string", true, null, List.of()));
    fields.put(
        "details",
        new FieldDefinition("description", "string", false, "Profile default", List.of()));
    profile = new Profile("bug", "APP", "Bug", fields, "Guidance only");
  }

  TicketPuller.Result pull(
      String key,
      boolean dryRun,
      boolean overwrite,
      Function<TicketPuller.Preview, Boolean> confirm)
      throws Exception {
    return puller.pull(history, "work", profiles, profile, input, key, dryRun, overwrite, confirm);
  }

  @Test
  void usesRecordedIssuePreservesBackupMapsAliasesAndNeverWritesToJira() throws Exception {
    new TicketPublisher(gateway, (field, value, alias) -> value)
        .publish(
            history,
            "work",
            profiles,
            profile,
            input,
            ProfileLoader.readInput(input),
            List.of(),
            "ask",
            key -> fail("First publication"));
    String original = Files.readString(input);
    var result =
        pull(
            null,
            false,
            false,
            preview -> {
              assertEquals("APP-42", preview.key());
              assertEquals(List.of("summary", "details"), preview.changedFields());
              return true;
            });
    assertTrue(result.written());
    assertEquals(original, Files.readString(result.backup()));
    assertEquals(
        Map.of("summary", "Remote", "details", "Remote details"), ProfileLoader.readInput(input));
    assertFalse(result.description().toString().contains("Remote details"));
    var second = pull(null, false, false, preview -> fail("No changes"));
    assertFalse(second.written());
    assertNull(second.backup());
    assertEquals(1, gateway.creates);
    assertEquals(0, gateway.updates);
  }

  @ParameterizedTest
  @ValueSource(strings = {"json", "yaml", "yml"})
  void supportsBothFormatsAndExplicitIssueBindsFuturePublication(String extension)
      throws Exception {
    input = directory.resolve("ticket." + extension);
    Files.writeString(input, extension.equals("json") ? "{}" : "# Preserve this in backup\n{}\n");
    String original = Files.readString(input);
    gateway.remoteDescription = "Résumé\nsecond line: yes";
    var result = pull("APP-42", false, true, preview -> fail("Overwrite approved"));
    assertEquals(original, Files.readString(result.backup()));
    assertEquals("Résumé\nsecond line: yes", ProfileLoader.readInput(input).get("details"));
    assertEquals(
        "APP-42",
        new TicketPublisher(gateway, (field, value, alias) -> value)
            .publish(
                history,
                "work",
                profiles,
                profile,
                input,
                ProfileLoader.readInput(input),
                List.of(),
                "update",
                key -> fail("Explicit update"))
            .key());
    assertEquals(0, gateway.creates);
    assertEquals(1, gateway.updates);
  }

  @Test
  void previewAndCancelLeaveInputAndBindingUntouched() throws Exception {
    String original = Files.readString(input);
    var preview = pull("APP-42", true, false, request -> fail("Dry run"));
    assertFalse(preview.written());
    assertEquals(original, Files.readString(input));
    assertFalse(Files.exists(history.resolve("backups")));
    assertEquals(
        CANCELLED,
        assertThrows(JiraFailure.class, () -> pull("APP-42", false, false, request -> false))
            .code());
    assertEquals(original, Files.readString(input));
    assertThrows(
        ProfileException.class, () -> pull(null, false, true, request -> fail("Missing binding")));
    assertEquals(0, gateway.creates);
    assertEquals(0, gateway.updates);
  }

  @Test
  void nullRemoteFieldsSuppressProfileDefaultsRatherThanKeepingStaleLocalValues() throws Exception {
    gateway.remoteDescription = null;
    pull("APP-42", false, true, request -> fail("Overwrite approved"));
    var values = ProfileLoader.readInput(input);
    assertTrue(values.containsKey("details"));
    assertNull(values.get("details"));
  }

  @Test
  void localEditsDuringConfirmationAreNotLost() throws Exception {
    assertThrows(
        ProfileException.class,
        () ->
            pull(
                "APP-42",
                false,
                false,
                request -> {
                  try {
                    Files.writeString(input, "{\"summary\":\"Edited during prompt\"}");
                  } catch (Exception failure) {
                    throw new AssertionError(failure);
                  }
                  return true;
                }));
    assertEquals("Edited during prompt", ProfileLoader.readInput(input).get("summary"));
  }

  @Test
  void movedIssuesMissingFieldsAndInvalidValuesFailBeforeWriting() throws Exception {
    String original = Files.readString(input);
    gateway.project = "OTHER";
    assertEquals(
        REMOTE_VALIDATION,
        assertThrows(JiraFailure.class, () -> pull("APP-42", false, true, request -> true)).code());
    gateway.project = "APP";
    gateway.omitDescription = true;
    assertEquals(
        REMOTE_VALIDATION,
        assertThrows(JiraFailure.class, () -> pull("APP-42", false, true, request -> true)).code());
    gateway.omitDescription = false;
    gateway.summary = "";
    assertThrows(ProfileException.class, () -> pull("APP-42", false, true, request -> true));
    assertEquals(original, Files.readString(input));
    assertFalse(Files.exists(history.resolve("backups")));
  }

  @Test
  void unresolvedSubmissionsRemainBlockedAndPullNeverGuesses() throws Exception {
    assertThrows(ProfileException.class, () -> pull(null, false, true, request -> true));
    assertEquals(0, gateway.reads);
    gateway.uncertain = true;
    assertThrows(
        JiraFailure.class,
        () ->
            new TicketPublisher(gateway, (field, value, alias) -> value)
                .publish(
                    history,
                    "work",
                    profiles,
                    profile,
                    input,
                    ProfileLoader.readInput(input),
                    List.of(),
                    "ask",
                    key -> null));
    assertEquals(
        UNCERTAIN_CREATION,
        assertThrows(JiraFailure.class, () -> pull("APP-42", false, true, request -> true)).code());
    assertEquals(0, gateway.reads);
  }

  class FakeGateway implements JiraGateway {
    int creates, updates, reads;
    String project = "APP";
    String summary = "Remote";
    String remoteDescription = "Remote details";
    boolean omitDescription, uncertain;

    public IssueSnapshot readIssue(String key, Set<String> ids) {
      reads++;
      assertEquals(Set.of("summary", "description"), ids);
      var fields = new LinkedHashMap<String, Object>();
      fields.put("summary", summary);
      if (!omitDescription) fields.put("description", remoteDescription);
      return new IssueSnapshot(key, "100", project, "2", "Bug", fields);
    }

    public Metadata metadata(String project, String type) {
      return new Metadata(
          "100",
          "2",
          Map.of(
              "summary", new Field("summary", "string", true, false, true, "literal", List.of()),
              "description",
                  new Field("description", "string", false, false, true, "literal", List.of())));
    }

    public Metadata editMetadata(String key) {
      return metadata("APP", "Bug");
    }

    public ExampleIssue example(String key) {
      throw new AssertionError();
    }

    public CreatedIssue create(Map<String, Object> payload) {
      creates++;
      if (uncertain) throw new JiraFailure(UNCERTAIN_CREATION, "Unresolved");
      return result();
    }

    public CreatedIssue update(String key, Map<String, Object> payload) {
      updates++;
      return result();
    }

    private CreatedIssue result() {
      return new CreatedIssue("APP-42", URI.create("https://example.invalid/browse/APP-42"));
    }
  }
}
