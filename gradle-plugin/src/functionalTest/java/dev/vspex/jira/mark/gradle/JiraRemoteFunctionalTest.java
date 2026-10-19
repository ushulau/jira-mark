package dev.vspex.jira.mark.gradle;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JiraRemoteFunctionalTest {
  @TempDir Path project;
  HttpServer server;
  AtomicInteger posts = new AtomicInteger();
  AtomicInteger requests = new AtomicInteger();
  List<String> authorizations = java.util.Collections.synchronizedList(new ArrayList<>());
  String baseUrl;
  static final String CREDENTIAL = "synthetic-config-cache-credential-one";

  @BeforeEach
  void prepare() throws Exception {
    Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
    Files.writeString(
        project.resolve("build.gradle"), "plugins { id 'base'; id 'dev.vspex.jira-mark' }\n");
    Path profiles = project.resolve("jira/profiles");
    Files.createDirectories(profiles);
    Files.writeString(
        profiles.resolve("bug.md"),
        """
        ---
        schemaVersion: 1
        profile: bug
        project: APP
        issueType: Bug
        fields:
          summary:
            required: true
        ---
        Local guidance.
        """);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          String path = exchange.getRequestURI().getPath();
          String response;
          int status = 200;
          if (exchange.getRequestMethod().equals("POST")) {
            posts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            status = 201;
            response = "{\"key\":\"APP-42\"}";
          } else if (path.startsWith("/rest/api/3/project/")) {
            response = "{\"id\":\"100\"}";
          } else if (path.endsWith("/issuetypes")) {
            response =
                "{\"startAt\":0,\"total\":1,\"issueTypes\":[{\"id\":\"2\",\"name\":\"Bug\"}]}";
          } else if (path.endsWith("/issuetypes/2")) {
            response =
                "{\"startAt\":0,\"total\":1,\"fields\":[{\"fieldId\":\"summary\",\"schema\":{\"type\":\"string\"},\"required\":true,\"operations\":[\"set\"]}]}";
          } else if (path.equals("/rest/api/3/issue/APP-1")) {
            response =
                "{\"key\":\"APP-1\",\"fields\":{\"project\":{\"id\":\"100\"},\"issuetype\":{\"id\":\"2\"}}}";
          } else {
            status = 404;
            response = "{}";
          }
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private GradleRunner runner(String credential, String... args) {
    var environment = new HashMap<>(System.getenv());
    environment.put("JIRA_TEST_URL", baseUrl);
    environment.put("JIRA_TEST_EMAIL", "tester@example.invalid");
    environment.put("JIRA_TEST_API_TOKEN", credential);
    return GradleRunner.create()
        .withProjectDir(project.toFile())
        .withPluginClasspath()
        .withEnvironment(environment)
        .withArguments(args);
  }

  @Test
  void creationRunsAgainWithReusedConfigurationAndFreshExecutionTimeCredentials() throws Exception {
    String[] args = {
      "jiraCreate",
      "--connection=test",
      "--ticket-profile=bug",
      "--field=summary=Synthetic",
      "--configuration-cache"
    };
    var first = runner(CREDENTIAL, args).build();
    assertTrue(first.getOutput().contains("APP-42"));
    assertEquals(1, posts.get());
    String rotated = "synthetic-config-cache-credential-two";
    var second = runner(rotated, args).build();
    assertTrue(second.getOutput().contains("Reusing configuration cache."));
    assertEquals(2, posts.get());
    assertTrue(authorizations.contains(basic(CREDENTIAL)));
    assertEquals(basic(rotated), authorizations.get(authorizations.size() - 1));
    assertFalse(first.getOutput().contains(CREDENTIAL));
    assertFalse(second.getOutput().contains(rotated));
    Path cache = project.resolve(".gradle/configuration-cache");
    try (var files = Files.walk(cache)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(content.contains(CREDENTIAL));
        assertFalse(content.contains(basic(CREDENTIAL)));
        assertFalse(content.contains(rotated));
      }
    }
  }

  @Test
  void buildCheckAndOfflineTasksNeverContactJira() {
    runner(
            CREDENTIAL,
            "build",
            "check",
            "jiraProfiles",
            "jiraPreview",
            "--ticket-profile=bug",
            "--field=summary=Synthetic",
            "--configuration-cache")
        .build();
    assertEquals(0, requests.get());
    assertEquals(0, posts.get());
  }

  @Test
  void validationAndImportNeverCreateAndImportDoesNotOverwrite() throws Exception {
    runner(
            CREDENTIAL,
            "jiraValidateRemote",
            "--connection=test",
            "--ticket-profile=bug",
            "--field=summary=Synthetic",
            "--configuration-cache")
        .build();
    String[] args = {
      "jiraImportProfile",
      "--connection=test",
      "--ticket-profile=imported",
      "--issue=APP-1",
      "--output=jira/profile-drafts/imported.md",
      "--configuration-cache"
    };
    runner(CREDENTIAL, args).build();
    Path output = project.resolve("jira/profile-drafts/imported.md");
    assertTrue(Files.readString(output).contains("profile: imported"));
    var result = runner(CREDENTIAL, args).buildAndFail();
    assertTrue(result.getOutput().contains("already exists"));
    assertEquals(0, posts.get());
    assertFalse(Files.exists(project.resolve("jira/profiles/imported.md")));
  }

  @Test
  void packagedPluginContainsBothAdaptersAndRunsWithoutCompositeDependencies() throws Exception {
    String jar = System.getProperty("jiraMark.pluginJar").replace('\\', '/');
    Files.writeString(
        project.resolve("build.gradle"),
        """
        buildscript {
          repositories { mavenCentral() }
          dependencies {
            classpath files('%s')
            classpath 'org.yaml:snakeyaml:2.6'
          }
        }
        apply plugin: 'dev.vspex.jira-mark'
        """
            .formatted(jar));
    // No withPluginClasspath(): this consumer loads the built artifact directly.
    var environment = new HashMap<>(System.getenv());
    environment.put("JIRA_TEST_URL", baseUrl);
    environment.put("JIRA_TEST_EMAIL", "tester@example.invalid");
    environment.put("JIRA_TEST_API_TOKEN", CREDENTIAL);
    var result =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withEnvironment(environment)
            .withArguments(
                "jiraValidateRemote",
                "--connection=test",
                "--ticket-profile=bug",
                "--field=summary=Synthetic",
                "--configuration-cache")
            .build();
    assertTrue(result.getOutput().contains("\"valid\":true"));
    assertEquals(0, posts.get());
  }

  private static String basic(String credential) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(
                ("tester@example.invalid:" + credential).getBytes(StandardCharsets.UTF_8));
  }
}
