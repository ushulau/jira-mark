package dev.vspex.jira.mark.cloud;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.profile.Profile;
import dev.vspex.jira.mark.profile.ProfileLoader;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.TicketPuller;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class IssuePullTest {
  @TempDir Path directory;
  HttpServer server;
  AtomicInteger mutations = new AtomicInteger();
  String query;

  CloudJiraGateway gateway(
      CloudConnection.Deployment deployment, boolean richText, boolean missingField)
      throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var connection =
        new CloudConnection(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jira"),
            "tester@example.invalid",
            "synthetic-pull-token",
            deployment);
    Object text =
        deployment == CloudConnection.Deployment.DATA_CENTER
            ? "h2. Changed\n* wiki text"
            : Map.of(
                "type",
                "doc",
                "version",
                1,
                "content",
                List.of(
                    Map.of(
                        "type",
                        "paragraph",
                        "content",
                        List.of(
                            richText
                                ? Map.of(
                                    "type",
                                    "text",
                                    "text",
                                    "Changed",
                                    "marks",
                                    List.of(Map.of("type", "strong")))
                                : Map.of("type", "text", "text", "Changed")))));
    var fields = new LinkedHashMap<String, Object>();
    fields.put("project", Map.of("id", "100", "key", "APP"));
    fields.put("issuetype", Map.of("id", "2", "name", "Bug"));
    fields.put("summary", "Remote summary");
    if (!missingField) fields.put("description", text);
    fields.put("labels", List.of("remote"));
    fields.put("priority", Map.of("id", "3", "name", "High", "self", "https://example.invalid"));
    fields.put("components", List.of(Map.of("id", "7", "name", "Backend")));
    fields.put("customfield_42", "Remote criteria");
    fields.put("comment", "Unrequested private content");
    server.createContext(
        "/",
        exchange -> {
          try {
            if (!exchange.getRequestMethod().equals("GET")) mutations.incrementAndGet();
            query = exchange.getRequestURI().getQuery();
            if (!exchange
                    .getRequestURI()
                    .getPath()
                    .equals("/jira" + connection.apiPath() + "/issue/APP-42")
                || !connection
                    .authorization()
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              CloudWorkflowTest.respond(exchange, 401, Map.of());
            } else
              CloudWorkflowTest.respond(exchange, 200, Map.of("key", "APP-42", "fields", fields));
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

  private Profile profile() {
    var fields = new LinkedHashMap<String, FieldDefinition>();
    fields.put("summary", new FieldDefinition("summary", "string", true, null, List.of()));
    fields.put("details", new FieldDefinition("description", "string", false, null, List.of()));
    fields.put("tags", new FieldDefinition("labels", "string-list", false, null, List.of()));
    fields.put("priority", new FieldDefinition("priority", "object", false, null, List.of()));
    fields.put("components", new FieldDefinition("components", "array", false, null, List.of()));
    fields.put("criteria", new FieldDefinition("customfield_42", "string", false, null, List.of()));
    return new Profile("bug", "APP", "Bug", fields, "Guidance only");
  }

  @ParameterizedTest
  @EnumSource(CloudConnection.Deployment.class)
  void pullsStandardFieldsWithoutAProfileIntoANewFile(CloudConnection.Deployment deployment)
      throws Exception {
    var gateway = gateway(deployment, false, false);
    Path profiles = directory.resolve("no-profiles");
    Path input = directory.resolve("tickets/nested/new.yaml");
    var puller = new TicketPuller(gateway, new CloudFieldDecoder());
    var result =
        puller.pull(
            directory.resolve("history"),
            "example",
            profiles,
            null,
            input,
            "APP-42",
            false,
            false,
            preview -> fail("No existing file to overwrite"));
    assertTrue(result.written());
    assertNull(result.backup());
    assertFalse(Files.exists(profiles));
    var pulled = ProfileLoader.readInput(input);
    assertEquals(
        java.util.Set.of("summary", "description", "labels", "priority", "components"),
        pulled.keySet());
    assertEquals("Remote summary", pulled.get("summary"));
    assertEquals(
        deployment == CloudConnection.Deployment.DATA_CENTER
            ? "h2. Changed\n* wiki text"
            : "Changed",
        pulled.get("description"));
    assertEquals(Map.of("id", "3"), pulled.get("priority"));
    assertEquals(List.of(Map.of("id", "7")), pulled.get("components"));
    assertFalse(
        puller
            .pull(
                directory.resolve("history"),
                "example",
                profiles,
                null,
                input,
                null,
                false,
                false,
                preview -> fail("No changes"))
            .written());
    assertEquals(0, mutations.get());
  }

  @ParameterizedTest
  @EnumSource(CloudConnection.Deployment.class)
  void readsOnlyConfiguredFieldsAndConvertsReferencesAliasesAndText(
      CloudConnection.Deployment deployment) throws Exception {
    var gateway = gateway(deployment, false, false);
    Path profiles = Files.createDirectories(directory.resolve("profiles"));
    Path input = directory.resolve("ticket.json");
    Files.writeString(input, "{}");
    var result =
        new TicketPuller(gateway, new CloudFieldDecoder())
            .pull(
                directory.resolve("history"),
                "work",
                profiles,
                profile(),
                input,
                "APP-42",
                false,
                true,
                preview -> fail("Overwrite approved"));
    var pulled = ProfileLoader.readInput(input);
    assertTrue(result.written());
    assertEquals("Remote summary", pulled.get("summary"));
    assertEquals(
        deployment == CloudConnection.Deployment.DATA_CENTER
            ? "h2. Changed\n* wiki text"
            : "Changed",
        pulled.get("details"));
    assertEquals(Map.of("id", "3"), pulled.get("priority"));
    assertEquals(List.of(Map.of("id", "7")), pulled.get("components"));
    assertEquals(List.of("remote"), pulled.get("tags"));
    assertEquals("Remote criteria", pulled.get("criteria"));
    assertFalse(Files.readString(input).contains("Unrequested"));
    assertEquals(
        "fields=components,customfield_42,description,issuetype,labels,priority,project,summary",
        query);
    assertEquals(0, mutations.get());
  }

  @Test
  void refusesRichTextInsteadOfSilentlyDiscardingFormatting() throws Exception {
    var gateway = gateway(CloudConnection.Deployment.CLOUD, true, false);
    Path profiles = Files.createDirectories(directory.resolve("profiles"));
    Path input = directory.resolve("ticket.yaml");
    Files.writeString(input, "summary: Preserve local\n");
    var failure =
        assertThrows(
            JiraFailure.class,
            () ->
                new TicketPuller(gateway, new CloudFieldDecoder())
                    .pull(
                        directory.resolve("history"),
                        "work",
                        profiles,
                        profile(),
                        input,
                        "APP-42",
                        false,
                        true,
                        preview -> true));
    assertTrue(failure.getMessage().contains("without losing data"));
    assertEquals("summary: Preserve local\n", Files.readString(input));
    assertFalse(Files.exists(directory.resolve("history/backups")));
    assertEquals(0, mutations.get());
  }

  @Test
  void missingFieldsDoNotClearLocalValues() throws Exception {
    var gateway = gateway(CloudConnection.Deployment.DATA_CENTER, false, true);
    var failure =
        assertThrows(
            JiraFailure.class,
            () -> gateway.readIssue("APP-42", java.util.Set.of("summary", "description")));
    assertTrue(failure.getMessage().contains("unavailable"));
    assertEquals(0, mutations.get());
  }
}
