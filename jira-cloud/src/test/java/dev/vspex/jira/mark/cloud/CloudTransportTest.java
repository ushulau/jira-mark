package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.vspex.jira.mark.profile.ProfileException;
import dev.vspex.jira.mark.remote.JiraFailure;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudTransportTest {
  HttpServer server;
  CloudConnection connection;
  CloudTransport http;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    connection =
        new CloudConnection(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "tester@example.invalid",
            "synthetic-test-credential");
    http = new CloudTransport(connection, Duration.ofMillis(300));
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void retriesReadsButDoesNotRetryRejectedMutations() {
    var requests = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          int attempt = requests.incrementAndGet();
          exchange.getResponseHeaders().add("Retry-After", "0");
          CloudWorkflowTest.respond(exchange, attempt < 3 ? 429 : 200, Map.of("ok", true));
          exchange.close();
        });
    assertEquals(true, http.get("/rest/api/3/project/APP").get("ok"));
    assertEquals(3, requests.get());
    requests.set(0);
    assertEquals(
        REMOTE_UNAVAILABLE,
        assertThrows(JiraFailure.class, () -> http.post("/rest/api/3/issue", Map.of())).code());
    assertEquals(1, requests.get());
  }

  @Test
  void doesNotIgnoreLongRetryAfter() {
    var requests = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.getResponseHeaders().add("Retry-After", "60");
          CloudWorkflowTest.respond(exchange, 429, Map.of());
          exchange.close();
        });
    assertEquals(
        REMOTE_UNAVAILABLE,
        assertThrows(JiraFailure.class, () -> http.get("/rest/api/3/project/APP")).code());
    assertEquals(1, requests.get());
  }

  @Test
  void redirectsNeverReceiveAuthorizationOrResubmitPosts() {
    var redirected = new AtomicInteger();
    server.createContext(
        "/target",
        exchange -> {
          redirected.incrementAndGet();
          exchange.close();
        });
    server.createContext(
        "/rest/",
        exchange -> {
          exchange.getResponseHeaders().add("Location", connection.baseUrl() + "/target");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    assertThrows(JiraFailure.class, () -> http.get("/rest/api/3/project/APP"));
    assertEquals(
        UNCERTAIN_CREATION,
        assertThrows(JiraFailure.class, () -> http.post("/rest/api/3/issue", Map.of())).code());
    assertEquals(0, redirected.get());
  }

  @Test
  void serverReportedPostTimeoutIsUncertainAndNeverRetried() {
    var requests = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          CloudWorkflowTest.respond(exchange, 408, Map.of());
          exchange.close();
        });
    assertEquals(
        UNCERTAIN_CREATION,
        assertThrows(JiraFailure.class, () -> http.post("/rest/api/3/issue", Map.of())).code());
    assertEquals(1, requests.get());
  }

  @Test
  void authenticationAndPermissionsProduceSafeDistinctFailures() {
    server.createContext(
        "/rest/api/3/auth",
        exchange -> {
          CloudWorkflowTest.respond(exchange, 401, Map.of("token", "private-remote-value"));
          exchange.close();
        });
    server.createContext(
        "/rest/api/3/permission",
        exchange -> {
          CloudWorkflowTest.respond(exchange, 403, Map.of());
          exchange.close();
        });
    var failure = assertThrows(JiraFailure.class, () -> http.get("/rest/api/3/auth"));
    assertEquals(AUTHENTICATION, failure.code());
    assertFalse(failure.toString().contains("private-remote-value"));
    assertEquals(
        PERMISSION_DENIED,
        assertThrows(JiraFailure.class, () -> http.get("/rest/api/3/permission")).code());
  }

  @Test
  void malformedJsonIsSanitized() {
    server.createContext(
        "/",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          byte[] bytes = "private-invalid-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    var failure = assertThrows(JiraFailure.class, () -> http.get("/rest/api/3/project/APP"));
    assertEquals(INVALID_RESPONSE, failure.code());
    assertFalse(failure.toString().contains("private-invalid-json"));
  }

  @Test
  void postTimeoutIsUncertainAndNeverRetried() {
    var requests = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          try {
            Thread.sleep(600);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    assertEquals(
        UNCERTAIN_CREATION,
        assertThrows(JiraFailure.class, () -> http.post("/rest/api/3/issue", Map.of())).code());
    assertEquals(1, requests.get());
  }

  @Test
  void connectionSelectionIsExplicitAndNeverPrintsCredentials() {
    var values =
        Map.of(
            "JIRA_TEST_URL",
            connection.baseUrl().toString(),
            "JIRA_TEST_EMAIL",
            "tester@example.invalid",
            "JIRA_TEST_API_TOKEN",
            "synthetic-test-credential");
    assertEquals(
        connection.baseUrl(), CloudConnection.fromEnvironment("test", values::get).baseUrl());
    assertThrows(ProfileException.class, () -> CloudConnection.fromEnvironment(null, values::get));
    assertThrows(
        ProfileException.class, () -> CloudConnection.fromEnvironment("other", values::get));
    assertFalse(connection.toString().contains("synthetic-test-credential"));
    assertThrows(
        ProfileException.class,
        () ->
            new CloudConnection(
                URI.create("http://example.invalid"), "tester@example.invalid", "synthetic"));
    assertThrows(
        ProfileException.class,
        () ->
            new CloudConnection(
                URI.create("https://user:secret@example.invalid"),
                "tester@example.invalid",
                "synthetic"));
  }
}
