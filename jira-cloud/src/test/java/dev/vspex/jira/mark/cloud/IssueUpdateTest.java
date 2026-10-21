package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraWorkflow;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class IssueUpdateTest {
  HttpServer server;
  AtomicInteger updates = new AtomicInteger();
  Map<?, ?> submitted;
  List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());

  CloudJiraGateway gateway(CloudConnection.Deployment deployment, int status) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var connection =
        new CloudConnection(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jira"),
            "tester@example.invalid",
            "synthetic-update-token",
            deployment);
    server.createContext(
        "/",
        exchange -> {
          try {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            if (!path.startsWith("/jira" + connection.apiPath() + "/")
                || !connection
                    .authorization()
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              CloudWorkflowTest.respond(exchange, 401, Map.of());
            } else if (exchange.getRequestMethod().equals("PUT")) {
              updates.incrementAndGet();
              submitted =
                  (Map<?, ?>)
                      Json.parse(
                          new String(
                              exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              if (status == 204) exchange.sendResponseHeaders(204, -1);
              else
                CloudWorkflowTest.respond(
                    exchange, status, Map.of("private", "private-remote-body"));
            } else if (path.endsWith("/editmeta")) {
              CloudWorkflowTest.respond(
                  exchange,
                  200,
                  Map.of(
                      "fields",
                      Map.of(
                          "summary", CloudWorkflowTest.field("summary", "string", true),
                          "description", CloudWorkflowTest.field("description", "string", false),
                          "labels", CloudWorkflowTest.field("labels", "array", false))));
            } else {
              CloudWorkflowTest.respond(
                  exchange,
                  200,
                  Map.of(
                      "key",
                      "APP-42",
                      "fields",
                      Map.of("project", Map.of("id", "100"), "issuetype", Map.of("id", "2"))));
            }
          } finally {
            exchange.close();
          }
        });
    server.start();
    return new CloudJiraGateway(connection, Duration.ofSeconds(2));
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @ParameterizedTest
  @EnumSource(CloudConnection.Deployment.class)
  void validatesAgainstEditMetadataAndAccepts204UsingTheSelectedEncoding(
      CloudConnection.Deployment deployment) throws Exception {
    var gateway = gateway(deployment, 204);
    var workflow = new JiraWorkflow(gateway, new CloudFieldEncoder());
    var payload =
        workflow.validateUpdate(
            CloudWorkflowTest.profile(),
            Map.of("summary", "Edited", "description", "*Text*\nNext"),
            List.of(),
            "APP-42",
            "100",
            "2");
    var result = gateway.update("APP-42", payload);
    assertEquals("APP-42", result.key());
    assertTrue(result.url().toString().endsWith("/jira/browse/APP-42"));
    assertEquals(1, updates.get());
    var fields = (Map<?, ?>) submitted.get("fields");
    assertFalse(fields.containsKey("project"));
    assertFalse(fields.containsKey("issuetype"));
    assertEquals("Edited", fields.get("summary"));
    if (deployment == CloudConnection.Deployment.DATA_CENTER)
      assertEquals("*Text*\nNext", fields.get("description"));
    else assertEquals("doc", ((Map<?, ?>) fields.get("description")).get("type"));
    assertTrue(requests.stream().anyMatch(path -> path.endsWith("/editmeta")));
    assertTrue(requests.stream().noneMatch(path -> path.contains("createmeta")));
  }

  @ParameterizedTest
  @EnumSource(CloudConnection.Deployment.class)
  void ambiguousUpdateIsNotRetriedAndDoesNotLeakResponseBodies(
      CloudConnection.Deployment deployment) throws Exception {
    var gateway = gateway(deployment, 503);
    var failure =
        assertThrows(
            JiraFailure.class,
            () -> gateway.update("APP-42", Map.of("fields", Map.of("summary", "Edited"))));
    assertEquals(UNCERTAIN_UPDATE, failure.code());
    assertEquals(1, updates.get());
    assertFalse(failure.toString().contains("private-remote-body"));
    assertFalse(failure.toString().contains("synthetic-update-token"));
  }
}
