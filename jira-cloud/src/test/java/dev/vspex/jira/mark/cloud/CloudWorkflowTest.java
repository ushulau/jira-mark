package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.profile.Profile;
import dev.vspex.jira.mark.profile.ProfileException;
import dev.vspex.jira.mark.profile.ProfileLoader;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraGateway;
import dev.vspex.jira.mark.remote.JiraWorkflow;
import dev.vspex.jira.mark.remote.ProfileImporter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudWorkflowTest {
  @TempDir Path directory;
  HttpServer server;
  CloudConnection connection;
  CloudJiraGateway gateway;
  AtomicInteger posts = new AtomicInteger();
  List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
  Map<String, Object> submitted;
  int postStatus = 201;
  Object postResponse = Map.of("key", "APP-42");
  List<Map<String, Object>> metadata;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    connection =
        new CloudConnection(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "tester@example.invalid",
            "synthetic-test-credential");
    gateway = new CloudJiraGateway(connection, Duration.ofSeconds(2));
    metadata =
        new ArrayList<>(
            List.of(
                field("summary", "string", true),
                field("description", "string", false),
                field("labels", "array", false)));
    server.createContext(
        "/",
        exchange -> {
          try {
            route(exchange);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @SuppressWarnings("unchecked")
  void route(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    requests.add(
        exchange.getRequestMethod() + " " + path + "?" + exchange.getRequestURI().getQuery());
    assertEquals(
        connection.authorization(), exchange.getRequestHeaders().getFirst("Authorization"));
    if (exchange.getRequestMethod().equals("POST")) {
      posts.incrementAndGet();
      submitted =
          (Map<String, Object>)
              Json.parse(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, postStatus, postResponse);
    } else if (path.equals("/rest/api/3/project/APP") || path.equals("/rest/api/3/project/100")) {
      respond(exchange, 200, Map.of("id", "100", "key", "APP"));
    } else if (path.equals("/rest/api/3/issue/createmeta/100/issuetypes")) {
      // Deliberately put the desired issue type on the second page.
      boolean first = exchange.getRequestURI().getQuery().contains("startAt=0");
      respond(
          exchange,
          200,
          Map.of(
              "startAt",
              first ? 0 : 1,
              "total",
              2,
              "issueTypes",
              List.of(Map.of("id", first ? "1" : "2", "name", first ? "Story" : "Bug"))));
    } else if (path.equals("/rest/api/3/issue/createmeta/100/issuetypes/2")) {
      int offset =
          Integer.parseInt(exchange.getRequestURI().getQuery().split("&")[0].split("=")[1]);
      respond(
          exchange,
          200,
          Map.of(
              "startAt",
              offset,
              "total",
              metadata.size(),
              "fields",
              metadata.subList(offset, Math.min(offset + 1, metadata.size()))));
    } else if (path.equals("/rest/api/3/issue/APP-1")) {
      respond(
          exchange,
          200,
          Map.of(
              "key",
              "APP-1",
              "fields",
              Map.of(
                  "project",
                  Map.of("id", "100"),
                  "issuetype",
                  Map.of("id", "2"),
                  "labels",
                  List.of("backend"),
                  "summary",
                  "Do not import this",
                  "description",
                  "Private source body")));
    } else {
      respond(exchange, 404, Map.of("message", "private-remote-body"));
    }
  }

  static void respond(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  static Map<String, Object> field(String id, String type, boolean required) {
    return Map.of(
        "fieldId",
        id,
        "schema",
        type.equals("array") ? Map.of("type", type, "items", "string") : Map.of("type", type),
        "operations",
        List.of("set"),
        "required",
        required,
        "hasDefaultValue",
        false);
  }

  static Profile profile() {
    var fields = new LinkedHashMap<String, FieldDefinition>();
    fields.put("summary", new FieldDefinition("summary", "string", true, null, List.of()));
    fields.put("description", new FieldDefinition("description", "string", false, null, List.of()));
    fields.put(
        "labels",
        new FieldDefinition("labels", "string-list", false, List.of("backend"), List.of()));
    return new Profile(
        "backend-bug", "APP", "Bug", fields, "Never copy this guidance into description");
  }

  @Test
  void followsAllMetadataPagesAndSubmitsOneEncodedIssue() {
    var result =
        new JiraWorkflow(gateway, new CloudFieldEncoder())
            .create(
                profile(),
                Map.of(
                    "summary", "Synthetic issue", "description", "**literal**\n\nSecond paragraph"),
                List.of());
    assertEquals("APP-42", result.key());
    assertEquals(connection.baseUrl() + "/browse/APP-42", result.url().toString());
    assertEquals(1, posts.get());
    assertTrue(requests.stream().anyMatch(path -> path.contains("issuetypes?startAt=1")));
    assertTrue(requests.stream().anyMatch(path -> path.contains("issuetypes/2?startAt=2")));
    var fields = (Map<?, ?>) submitted.get("fields");
    assertEquals(Map.of("id", "100"), fields.get("project"));
    assertEquals(Map.of("id", "2"), fields.get("issuetype"));
    assertEquals(List.of("backend"), fields.get("labels"));
    assertEquals("doc", ((Map<?, ?>) fields.get("description")).get("type"));
    assertTrue(Json.write(fields.get("description")).contains("**literal**"));
    assertFalse(Json.write(submitted).contains("guidance"));
  }

  @Test
  void remoteValidationDoesNotPostAndFindsNewRequiredFields() {
    var workflow = new JiraWorkflow(gateway, new CloudFieldEncoder());
    workflow.validate(profile(), Map.of("summary", "Synthetic"), List.of());
    assertEquals(0, posts.get());
    metadata.add(field("customfield_42", "string", true));
    var failure =
        assertThrows(
            JiraFailure.class,
            () -> workflow.create(profile(), Map.of("summary", "Synthetic"), List.of()));
    assertEquals(REMOTE_VALIDATION, failure.code());
    assertTrue(failure.getMessage().contains("customfield_42"));
    assertEquals(0, posts.get());
  }

  @Test
  void remoteRequiredDescriptionRejectsEmptyTextBeforeAdfEncoding() {
    metadata.set(1, field("description", "string", true));
    var failure =
        assertThrows(
            JiraFailure.class,
            () ->
                new JiraWorkflow(gateway, new CloudFieldEncoder())
                    .create(
                        profile(), Map.of("summary", "Synthetic", "description", ""), List.of()));
    assertEquals(REMOTE_VALIDATION, failure.code());
    assertEquals(0, posts.get());
  }

  @Test
  void incompletePaginationFailsInsteadOfSilentlyAcceptingPartialMetadata() {
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          if (exchange.getRequestURI().getPath().contains("issuetypes")) {
            respond(exchange, 200, Map.of("startAt", 0, "total", 2, "issueTypes", List.of()));
          } else {
            respond(exchange, 200, Map.of("id", "100"));
          }
          exchange.close();
        });
    assertEquals(
        INVALID_RESPONSE,
        assertThrows(JiraFailure.class, () -> gateway.metadata("APP", "Bug")).code());
    assertEquals(0, posts.get());
  }

  @Test
  void malformedMetadataChoicesProduceSafeStructuredFailure() {
    var bad = new LinkedHashMap<>(field("summary", "string", true));
    bad.put("allowedValues", java.util.Arrays.asList((Object) null));
    metadata.set(0, bad);
    assertEquals(
        INVALID_RESPONSE,
        assertThrows(JiraFailure.class, () -> gateway.metadata("APP", "Bug")).code());
  }

  @Test
  void localFailuresDoNotContactJira() {
    assertThrows(
        ProfileException.class,
        () ->
            new JiraWorkflow(gateway, new CloudFieldEncoder())
                .create(profile(), Map.of(), List.of()));
    assertTrue(requests.isEmpty());
  }

  @Test
  void unavailableProfileFieldFailsBeforeSubmission() {
    metadata.removeIf(field -> field.get("fieldId").equals("labels"));
    var failure =
        assertThrows(
            JiraFailure.class,
            () ->
                new JiraWorkflow(gateway, new CloudFieldEncoder())
                    .create(profile(), Map.of("summary", "Synthetic"), List.of()));
    assertEquals(REMOTE_VALIDATION, failure.code());
    assertEquals(0, posts.get());
  }

  @Test
  void ambiguousTypeNameFailsRatherThanGuessing() {
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          if (exchange.getRequestURI().getPath().contains("issuetypes")) {
            respond(
                exchange,
                200,
                Map.of(
                    "startAt",
                    0,
                    "total",
                    2,
                    "issueTypes",
                    List.of(Map.of("id", "1", "name", "Bug"), Map.of("id", "2", "name", "Bug"))));
          } else {
            respond(exchange, 200, Map.of("id", "100"));
          }
          exchange.close();
        });
    assertEquals(
        REMOTE_VALIDATION,
        assertThrows(JiraFailure.class, () -> gateway.metadata("APP", "Bug")).code());
  }

  @Test
  void uncertainServerFailureIsNeverRetriedAndResponseIsNotLeaked() {
    postStatus = 503;
    postResponse = Map.of("secret", "private-remote-body");
    var failure =
        assertThrows(
            JiraFailure.class,
            () ->
                new JiraWorkflow(gateway, new CloudFieldEncoder())
                    .create(profile(), Map.of("summary", "Synthetic"), List.of()));
    assertEquals(UNCERTAIN_CREATION, failure.code());
    assertFalse(failure.toString().contains("private-remote-body"));
    assertEquals(1, posts.get());
  }

  @Test
  void malformedSuccessIsUncertainAndNeverResubmitted() {
    postResponse = Map.of("unexpected", "private-remote-body");
    assertEquals(
        UNCERTAIN_CREATION,
        assertThrows(JiraFailure.class, () -> gateway.create(Map.of("fields", Map.of()))).code());
    assertEquals(1, posts.get());
  }

  @Test
  void importUsesOnlyExplicitReusableDefaultsAndNeverActivatesOrOverwrites() throws Exception {
    Path active = directory.resolve("profiles");
    Files.createDirectories(active);
    var importer = new ProfileImporter(gateway);
    Path output = directory.resolve("drafts/bug.md");
    importer.importProfile("APP-1", "backend-bug", Set.of("labels"), output, active);
    var imported = ProfileLoader.load(output);
    assertEquals(List.of("backend"), imported.fields().get("labels").defaultValue());
    assertNull(imported.fields().get("summary").defaultValue());
    assertNull(imported.fields().get("description").defaultValue());
    assertFalse(Files.readString(output).contains("Private source body"));
    assertFalse(Files.readString(output).contains("Do not import this"));
    try (var files = Files.list(active)) {
      assertEquals(0, files.count());
    }
    assertThrows(
        ProfileException.class,
        () -> importer.importProfile("APP-1", "backend-bug", Set.of(), output, active));
    assertThrows(
        ProfileException.class,
        () ->
            importer.importProfile(
                "APP-1", "backend-bug", Set.of(), active.resolve("draft.md"), active));
    assertThrows(
        ProfileException.class,
        () ->
            importer.importProfile(
                "APP-1", "backend-bug", Set.of("summary"), directory.resolve("other.md"), active));
    assertEquals(0, posts.get());
  }

  @Test
  void importWithNoReuseKeepsAllDefaultsEmptyAndRecordsUnsupportedFields() throws Exception {
    metadata.add(field("assignee", "user", true));
    Path output = directory.resolve("draft.md");
    var result =
        new ProfileImporter(gateway)
            .importProfile("APP-1", "bug", Set.of(), output, directory.resolve("active"));
    assertEquals(1, result.reviewNotes().size());
    var imported = ProfileLoader.load(output);
    assertTrue(imported.fields().values().stream().allMatch(field -> field.defaultValue() == null));
    assertFalse(imported.fields().containsKey("assignee"));
  }

  @Test
  void strictFieldEncodingResolvesUniqueChoicesAndRejectsArbitraryObjects() {
    var encoder = new CloudFieldEncoder();
    var priority =
        new JiraGateway.Field(
            "priority",
            "object",
            false,
            false,
            true,
            "reference",
            List.of(Map.of("id", "3", "name", "Medium")));
    assertEquals(Map.of("id", "3"), encoder.encode(priority, Map.of("name", "Medium"), "priority"));
    assertThrows(
        JiraFailure.class,
        () -> encoder.encode(priority, Map.of("name", "Missing private value"), "priority"));
    assertThrows(
        JiraFailure.class,
        () -> encoder.encode(priority, Map.of("id", "3", "extra", "private"), "priority"));
    assertThrows(
        JiraFailure.class,
        () ->
            encoder.encode(
                new JiraGateway.Field(
                    "unknown", null, false, false, true, "unsupported", List.of()),
                Map.of(),
                "unknown"));
  }
}
