package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.profile.ProfileLoader;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraWorkflow;
import dev.vspex.jira.mark.remote.ProfileImporter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataCenterWorkflowTest {
  @TempDir Path directory;
  HttpServer server;
  CloudConnection connection;
  CloudJiraGateway gateway;
  List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());
  AtomicInteger posts = new AtomicInteger();
  Map<?, ?> submitted;
  int postStatus = 201;
  boolean incompleteMetadata;
  final List<Map<String, Object>> fields =
      List.of(
          field("summary", Map.of("type", "string"), true),
          field("description", Map.of("type", "string"), false),
          field("environment", Map.of("type", "string"), false),
          field(
              "customfield_42",
              Map.of(
                  "type",
                  "string",
                  "custom",
                  "com.atlassian.jira.plugin.system.customfieldtypes:textarea"),
              false),
          field("labels", Map.of("type", "array", "items", "string"), false),
          field("components", Map.of("type", "array", "items", "component"), false));

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var properties =
        Map.of(
            "jira.work.url",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/jira",
            "jira.work.type",
            "data-center",
            "jira.work.token",
            "synthetic-dc-pat");
    connection = CloudConnection.fromConfiguration("work", properties::get, key -> null);
    gateway = new CloudJiraGateway(connection, Duration.ofSeconds(2));
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

  void route(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    paths.add(path + "?" + exchange.getRequestURI().getQuery());
    if (!"Bearer synthetic-dc-pat".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      respond(exchange, 401, Map.of());
      return;
    }
    if (!path.startsWith("/jira/rest/api/2/")) {
      respond(exchange, 404, Map.of());
    } else if (path.endsWith("/project/100") || path.endsWith("/project/APP")) {
      respond(exchange, 200, Map.of("id", "100"));
    } else if (path.endsWith("/issuetypes")) {
      int start = offset(exchange);
      respond(
          exchange,
          200,
          Map.of(
              "startAt",
              start,
              "total",
              2,
              "isLast",
              start == 1,
              "values",
              List.of(Map.of("id", start == 0 ? "1" : "2", "name", start == 0 ? "Story" : "Bug"))));
    } else if (path.endsWith("/issuetypes/2")) {
      int start = offset(exchange);
      int end = Math.min(start + 2, fields.size());
      respond(
          exchange,
          200,
          Map.of(
              "startAt",
              start,
              "total",
              fields.size(),
              "isLast",
              false,
              "values",
              incompleteMetadata ? List.of() : fields.subList(start, end)));
    } else if (path.endsWith("/issue/APP-1")) {
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
                  "components",
                  List.of(Map.of("id", "7", "name", "Backend")),
                  "description",
                  "Private source body")));
    } else if (path.endsWith("/issue") && exchange.getRequestMethod().equals("POST")) {
      posts.incrementAndGet();
      submitted =
          (Map<?, ?>)
              Json.parse(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, postStatus, Map.of("key", "APP-42"));
    } else {
      respond(exchange, 404, Map.of());
    }
  }

  @Test
  void importsReusableDefaultsAndValidatesAndCreatesUsingTextAndApiV2() throws Exception {
    Path draft = directory.resolve("drafts/imported.md");
    new ProfileImporter(gateway)
        .importProfile(
            "APP-1",
            "imported",
            Set.of("labels", "components"),
            draft,
            directory.resolve("profiles"));
    var profile = ProfileLoader.load(draft);
    var input =
        Map.<String, Object>of(
            "summary",
            "Synthetic",
            "description",
            "h2. Details\n* wiki markup",
            "environment",
            "Linux",
            "customfield_42",
            "*Custom text*");
    var workflow = new JiraWorkflow(gateway, new CloudFieldEncoder());
    workflow.validate(profile, input, List.of());
    assertEquals(0, posts.get());
    var created = workflow.create(profile, input, List.of());
    assertEquals(1, posts.get());
    assertEquals(connection.baseUrl() + "/browse/APP-42", created.url().toString());
    var payload = (Map<?, ?>) submitted.get("fields");
    for (String textField : List.of("description", "environment", "customfield_42")) {
      assertEquals(input.get(textField), payload.get(textField));
      assertInstanceOf(String.class, payload.get(textField));
    }
    assertEquals(List.of("backend"), payload.get("labels"));
    assertEquals(List.of(Map.of("id", "7")), payload.get("components"));
    assertTrue(paths.stream().allMatch(path -> path.startsWith("/jira/rest/api/2/")));
    assertTrue(paths.stream().anyMatch(path -> path.contains("issuetypes?startAt=1")));
    assertTrue(paths.stream().anyMatch(path -> path.contains("issuetypes/2?startAt=4")));
    assertFalse(Files.readString(draft).contains("Private source body"));
    assertFalse(Files.readString(draft).contains("synthetic-dc-pat"));
  }

  @Test
  void incompleteDataCenterPaginationFailsInsteadOfAcceptingPartialMetadata() {
    incompleteMetadata = true;
    assertEquals(
        INVALID_RESPONSE,
        assertThrows(JiraFailure.class, () -> gateway.metadata("APP", "Bug")).code());
    assertEquals(0, posts.get());
  }

  @Test
  void rejectedCreationIsNotRetriedAndDoesNotExposeThePat() {
    postStatus = 401;
    var failure = assertThrows(JiraFailure.class, () -> gateway.create(Map.of("fields", Map.of())));
    assertEquals(AUTHENTICATION, failure.code());
    assertEquals(1, posts.get());
    assertFalse(failure.toString().contains("synthetic-dc-pat"));
  }

  @Test
  void ambiguousCreationIsNotRetried() {
    postStatus = 500;
    assertEquals(
        UNCERTAIN_CREATION,
        assertThrows(JiraFailure.class, () -> gateway.create(Map.of("fields", Map.of()))).code());
    assertEquals(1, posts.get());
  }

  private static int offset(HttpExchange exchange) {
    return Integer.parseInt(exchange.getRequestURI().getQuery().split("&")[0].split("=")[1]);
  }

  private static Map<String, Object> field(
      String id, Map<String, Object> schema, boolean required) {
    return Map.of(
        "fieldId", id, "schema", schema, "required", required, "operations", List.of("set"));
  }

  private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}
