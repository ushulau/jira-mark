package dev.vspex.jira.mark.remote;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.profile.Profile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TicketPublisherTest {
  @TempDir Path directory;
  Path profiles;
  Path file;
  Path history;
  FakeGateway gateway = new FakeGateway();
  TicketPublisher publisher = new TicketPublisher(gateway, (field, value, alias) -> value);
  Profile profile;

  @BeforeEach
  void prepare() throws Exception {
    profiles = Files.createDirectories(directory.resolve("profiles"));
    file = directory.resolve("ticket.json");
    Files.writeString(file, "{}");
    history = directory.resolve("history");
    var fields = new LinkedHashMap<String, FieldDefinition>();
    fields.put("summary", new FieldDefinition("summary", "string", true, null, List.of()));
    fields.put("description", new FieldDefinition("description", "string", false, null, List.of()));
    profile = new Profile("bug", "APP", "Bug", fields, "Guidance only");
  }

  JiraGateway.CreatedIssue publish(
      Map<String, Object> values, String policy, Function<String, TicketPublisher.Decision> choice)
      throws Exception {
    return publisher.publish(
        history, "work", profiles, profile, file, values, List.of(), policy, choice);
  }

  @Test
  void editsToTheSameFileOfferUpdateWithoutMovingTheIssueOrClearingOmittedFields()
      throws Exception {
    var first =
        publish(
            Map.of("summary", "Original", "description", "Keep this"),
            "ask",
            key -> fail("No repeat"));
    Files.writeString(file, "{\"summary\":\"Changed\"}");
    var second =
        publish(
            Map.of("summary", "Changed"),
            "ask",
            key -> {
              assertEquals(first.key(), key);
              return TicketPublisher.Decision.UPDATE;
            });
    assertEquals(first.key(), second.key());
    assertEquals(1, gateway.creates);
    assertEquals(1, gateway.updates);
    assertEquals(Map.of("fields", Map.of("summary", "Changed")), gateway.updated);
    assertEquals(first.key(), gateway.updatedKey);
  }

  @Test
  void updatePolicyWithoutMatchingHistoryNeverCreates() throws Exception {
    var failure =
        assertThrows(
            dev.vspex.jira.mark.profile.ProfileException.class,
            () -> publish(Map.of("summary", "Edited"), "update", key -> fail("No prompt")));
    assertTrue(failure.getMessage().contains("No matching publication record"));
    assertEquals(0, gateway.creates);
    assertEquals(0, gateway.updates);
  }

  @Test
  void explicitUpdateBindsExistingIssueAndNeverCreates() throws Exception {
    publisher.update(
        history,
        "example",
        profiles,
        profile,
        file,
        Map.of("summary", "Edited"),
        List.of(),
        "APP-42");
    publisher.update(
        history,
        "example",
        profiles,
        profile,
        file,
        Map.of("summary", "Edited again"),
        List.of(),
        null);
    assertEquals("APP-42", gateway.updatedKey);
    assertEquals(2, gateway.updates);
    assertEquals(0, gateway.creates);
    assertThrows(
        dev.vspex.jira.mark.profile.ProfileException.class,
        () ->
            publisher.update(
                history,
                "example",
                profiles,
                profile,
                file,
                Map.of("summary", "Wrong target"),
                List.of(),
                "APP-99"));
    assertEquals(2, gateway.updates);
  }

  @Test
  void explicitNewChoiceRecordsTheLatestCreatedIssue() throws Exception {
    publish(Map.of("summary", "Original"), "ask", key -> fail("No repeat"));
    var newer = publish(Map.of("summary", "Changed"), "create", key -> fail("Explicit choice"));
    publish(Map.of("summary", "Updated again"), "update", key -> fail("Explicit choice"));
    assertEquals(2, gateway.creates);
    assertEquals(newer.key(), gateway.updatedKey);
  }

  @Test
  void cancellationAndMissingConsoleNeverSubmitAgain() throws Exception {
    publish(Map.of("summary", "Original"), "ask", key -> fail("No repeat"));
    assertEquals(
        CANCELLED,
        assertThrows(
                JiraFailure.class,
                () ->
                    publish(Map.of("summary", "Changed"), "cancel", key -> fail("Explicit choice")))
            .code());
    assertEquals(
        CANCELLED,
        assertThrows(
                JiraFailure.class,
                () ->
                    publish(
                        Map.of("summary", "Changed"),
                        "ask",
                        key -> InteractiveInput.chooseExisting(key, null, System.err)))
            .code());
    assertEquals(1, gateway.creates);
    assertEquals(0, gateway.updates);
  }

  @Test
  void recordsOnlyConfirmedKeysAndIdentifiersNotTicketContents() throws Exception {
    publish(
        Map.of("summary", "Private summary", "description", "Private details"),
        "ask",
        key -> fail("No repeat"));
    try (var files = Files.list(history)) {
      for (Path path : files.toList()) {
        String text = Files.readString(path);
        assertFalse(text.contains("Private"));
        assertFalse(text.contains(file.toString()));
      }
    }
    gateway.editProject = "another-project";
    assertEquals(
        REMOTE_VALIDATION,
        assertThrows(
                JiraFailure.class,
                () ->
                    publish(Map.of("summary", "Changed"), "update", key -> fail("Explicit choice")))
            .code());
    assertEquals(0, gateway.updates);
  }

  @Test
  void uncertainCreationOrUpdateBlocksFurtherSubmissions() throws Exception {
    gateway.failure = new JiraFailure(UNCERTAIN_CREATION, "Unknown creation outcome");
    assertEquals(
        UNCERTAIN_CREATION,
        assertThrows(
                JiraFailure.class,
                () -> publish(Map.of("summary", "Original"), "ask", key -> fail("No repeat")))
            .code());
    gateway.failure = null;
    assertEquals(
        UNCERTAIN_CREATION,
        assertThrows(
                JiraFailure.class,
                () -> publish(Map.of("summary", "Original"), "create", key -> fail("Must not ask")))
            .code());
    assertEquals(1, gateway.creates);
    try (var files = Files.list(history)) {
      for (Path record : files.filter(path -> path.toString().endsWith(".json")).toList())
        Files.delete(record);
    }
    publish(Map.of("summary", "Original"), "ask", key -> fail("No repeat"));
    gateway.failure = new JiraFailure(UNCERTAIN_UPDATE, "Unknown update outcome");
    assertEquals(
        UNCERTAIN_UPDATE,
        assertThrows(
                JiraFailure.class,
                () ->
                    publish(Map.of("summary", "Changed"), "update", key -> fail("Explicit choice")))
            .code());
    gateway.failure = null;
    assertEquals(
        UNCERTAIN_UPDATE,
        assertThrows(
                JiraFailure.class,
                () -> publish(Map.of("summary", "Changed"), "create", key -> fail("Must not ask")))
            .code());
    assertEquals(1, gateway.updates);
  }

  @Test
  void definiteRejectionPermitsRetryAndCorruptHistoryNeverCreates() throws Exception {
    gateway.failure = new JiraFailure(AUTHENTICATION, "Rejected");
    assertThrows(
        JiraFailure.class,
        () -> publish(Map.of("summary", "Original"), "ask", key -> fail("No repeat")));
    gateway.failure = null;
    publish(Map.of("summary", "Original"), "ask", key -> fail("Not previously published"));
    try (var files = Files.list(history)) {
      Path record =
          files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
      Files.writeString(record, "not json");
    }
    assertThrows(
        dev.vspex.jira.mark.profile.ProfileException.class,
        () -> publish(Map.of("summary", "Original"), "create", key -> fail("Invalid history")));
    assertEquals(2, gateway.creates);
  }

  @Test
  void connectionAndDeploymentScopePreventUpdatingAnotherSite() throws Exception {
    publish(Map.of("summary", "Original"), "ask", key -> fail("No repeat"));
    publisher.publish(
        history,
        "personal",
        profiles,
        profile,
        file,
        Map.of("summary", "Original"),
        List.of(),
        "ask",
        key -> fail("Different connection"));
    gateway.scope = "another-site";
    publish(Map.of("summary", "Original"), "ask", key -> fail("Different deployment"));
    assertEquals(3, gateway.creates);
  }

  @Test
  void contentOnlyIdentityIsStableAcrossMapOrderingAndLocksPreventConcurrentPublication()
      throws Exception {
    publisher.publish(
        history,
        "work",
        profiles,
        profile,
        null,
        Map.of("summary", "Original", "description", "Details"),
        List.of(),
        "ask",
        key -> fail("No repeat"));
    var reordered = new LinkedHashMap<String, Object>();
    reordered.put("description", "Details");
    reordered.put("summary", "Original");
    assertEquals(
        CANCELLED,
        assertThrows(
                JiraFailure.class,
                () ->
                    publisher.publish(
                        history,
                        "work",
                        profiles,
                        profile,
                        null,
                        reordered,
                        List.of(),
                        "ask",
                        key -> {
                          var busy =
                              assertThrows(
                                  JiraFailure.class,
                                  () ->
                                      publisher.publish(
                                          history,
                                          "work",
                                          profiles,
                                          profile,
                                          null,
                                          reordered,
                                          List.of(),
                                          "create",
                                          ignored -> fail("Locked")));
                          assertTrue(busy.getMessage().contains("in progress"));
                          return TicketPublisher.Decision.CANCEL;
                        }))
            .code());
    assertEquals(1, gateway.creates);
  }

  class FakeGateway implements JiraGateway {
    int creates;
    int updates;
    String scope = "test-site";
    String editProject = "100";
    String updatedKey;
    Map<String, Object> updated;
    JiraFailure failure;

    public Metadata metadata(String project, String issueType) {
      return new Metadata(
          "100",
          "2",
          Map.of(
              "summary", new Field("summary", "string", true, false, true, "literal", List.of()),
              "description",
                  new Field("description", "string", false, false, true, "literal", List.of())));
    }

    public Metadata editMetadata(String key) {
      var fields = new LinkedHashMap<>(metadata("APP", "Bug").fields());
      fields.put(
          "customfield_99",
          new Field("customfield_99", "string", true, false, true, "literal", List.of()));
      return new Metadata(editProject, "2", fields);
    }

    public IssueSnapshot readIssue(String key, java.util.Set<String> fields) {
      return new IssueSnapshot(key, "100", "APP", "2", "Bug", Map.of());
    }

    public ExampleIssue example(String key) {
      throw new AssertionError();
    }

    public CreatedIssue create(Map<String, Object> payload) {
      creates++;
      if (failure != null) throw failure;
      return result("APP-" + creates);
    }

    public CreatedIssue update(String key, Map<String, Object> payload) {
      updates++;
      if (failure != null) throw failure;
      updatedKey = key;
      updated = payload;
      return result(key);
    }

    public String publicationScope() {
      return scope;
    }

    private CreatedIssue result(String key) {
      return new CreatedIssue(key, URI.create("https://example.invalid/browse/" + key));
    }
  }
}
