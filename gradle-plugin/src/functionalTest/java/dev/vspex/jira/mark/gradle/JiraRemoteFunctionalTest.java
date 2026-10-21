package dev.vspex.jira.mark.gradle;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.vspex.jira.mark.json.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  AtomicInteger updates = new AtomicInteger();
  Map<?, ?> updatedPayload;
  AtomicInteger requests = new AtomicInteger();
  List<String> authorizations = java.util.Collections.synchronizedList(new ArrayList<>());
  List<String> apiPaths = java.util.Collections.synchronizedList(new ArrayList<>());
  boolean dataCenter;
  String remoteSummary = "Edited in Jira";
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
          apiPaths.add(path);
          if (dataCenter) {
            path = path.replace("/rest/api/2/", "/rest/api/3/");
          }
          String response;
          int status = 200;
          if (exchange.getRequestMethod().equals("PUT")) {
            updates.incrementAndGet();
            updatedPayload =
                (Map<?, ?>)
                    Json.parse(
                        new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
          } else if (exchange.getRequestMethod().equals("POST")) {
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
          } else if (path.equals("/rest/api/3/issue/APP-42")
              && exchange.getRequestURI().getQuery() != null
              && exchange.getRequestURI().getQuery().contains("summary")) {
            response =
                Json.write(
                    Map.of(
                        "key",
                        "APP-42",
                        "fields",
                        Map.of(
                            "project",
                            Map.of("id", "100", "key", "APP"),
                            "issuetype",
                            Map.of("id", "2", "name", "Bug"),
                            "summary",
                            remoteSummary)));
          } else if (path.endsWith("/editmeta")) {
            response =
                "{\"fields\":{\"summary\":{\"schema\":{\"type\":\"string\"},\"required\":true,\"operations\":[\"set\"]}}}";
          } else if (path.equals("/rest/api/3/issue/APP-1")
              || path.equals("/rest/api/3/issue/APP-42")) {
            response =
                "{\"key\":\"APP-1\",\"fields\":{\"project\":{\"id\":\"100\"},\"issuetype\":{\"id\":\"2\"}}}";
          } else {
            status = 404;
            response = "{}";
          }
          if (dataCenter) {
            response =
                response
                    .replace("\"issueTypes\":", "\"values\":")
                    .replace("\"fields\":[", "\"values\":[");
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
      "--on-existing=create",
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

  @Test
  void missingRemoteProfileExplainsTheOptionWithoutContactingJira() {
    String[] importArgs = {
      "jiraImportProfile",
      "--connection=test",
      "--issue=APP-1",
      "--output=jira/profiles/new-profile.md",
      "--reuse-default=labels",
      "--reuse-default=components",
      "--configuration-cache"
    };
    var first = runner(CREDENTIAL, importArgs).buildAndFail();
    assertTrue(
        first.getOutput().contains("INVALID_INPUT: Import requires --ticket-profile=<name>"));
    assertTrue(first.getOutput().contains("new draft profile"));
    assertFalse(first.getOutput().contains("property 'profile' doesn't have a configured value"));
    var second = runner(CREDENTIAL, importArgs).buildAndFail();
    assertTrue(second.getOutput().contains("Reusing configuration cache."));
    assertTrue(second.getOutput().contains("Import requires --ticket-profile=<name>"));
    for (String task : List.of("jiraValidateRemote", "jiraCreate")) {
      var result =
          runner(CREDENTIAL, task, "--connection=test", "--configuration-cache").buildAndFail();
      assertTrue(
          result
              .getOutput()
              .contains("INVALID_INPUT: This operation requires --ticket-profile=<name>"));
    }
    assertEquals(0, requests.get());
    assertEquals(0, posts.get());
  }

  @Test
  void missingConnectionExplainsRepositoryDefaultsWithoutContactingJira() {
    var result =
        runner(
                CREDENTIAL,
                "jiraImportProfile",
                "--ticket-profile=new-profile",
                "--issue=APP-1",
                "--output=jira/profile-drafts/new-profile.md",
                "--configuration-cache")
            .buildAndFail();
    assertTrue(
        result
            .getOutput()
            .contains("INVALID_INPUT: Remote operations require --connection=<name>"));
    assertTrue(result.getOutput().contains("jira.connection"));
    assertEquals(0, requests.get());
  }

  @Test
  void importRejectsActiveProfileOutputBeforeContactingJira() {
    var result =
        runner(
                CREDENTIAL,
                "jiraImportProfile",
                "--connection=test",
                "--ticket-profile=new-profile",
                "--issue=APP-1",
                "--output=jira/profiles/new-profile.md",
                "--configuration-cache")
            .buildAndFail();
    assertTrue(
        result.getOutput().contains("Import drafts must be outside the active profiles directory"));
    assertEquals(0, requests.get());
    assertFalse(Files.exists(project.resolve("jira/profiles/new-profile.md")));
  }

  private static String basic(String credential) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(
                ("tester@example.invalid:" + credential).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void dataCenterPropertiesUseBearerAndFreshCredentialsWithConfigurationCache() throws Exception {
    dataCenter = true;
    String[] args = {
      "jiraCreate",
      "--connection=test",
      "--ticket-profile=bug",
      "--field=summary=Synthetic",
      "-Pjira.test.type=data-center",
      "-Pjira.test.email=",
      "--on-existing=create",
      "--configuration-cache"
    };
    var first = runner(CREDENTIAL, args).build();
    assertTrue(first.getOutput().contains("APP-42"));
    assertTrue(authorizations.stream().allMatch(value -> value.equals("Bearer " + CREDENTIAL)));
    String rotated = "synthetic-dc-rotated-pat";
    var second = runner(rotated, args).build();
    assertTrue(second.getOutput().contains("Reusing configuration cache."));
    assertEquals("Bearer " + rotated, authorizations.get(authorizations.size() - 1));
    assertEquals(2, posts.get());
    runner(
            rotated,
            "jiraImportProfile",
            "--connection=test",
            "--ticket-profile=imported",
            "--issue=APP-1",
            "--output=jira/profile-drafts/imported.md",
            "-Pjira.test.type=data-center",
            "-Pjira.test.email=",
            "--configuration-cache")
        .build();
    assertTrue(Files.exists(project.resolve("jira/profile-drafts/imported.md")));
    assertEquals(2, posts.get());
    assertTrue(apiPaths.stream().allMatch(path -> path.startsWith("/rest/api/2/")));
    assertFalse(first.getOutput().contains(CREDENTIAL));
    assertFalse(second.getOutput().contains(rotated));
    try (var files = Files.walk(project.resolve(".gradle/configuration-cache"))) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(content.contains(CREDENTIAL));
        assertFalse(content.contains(rotated));
      }
    }
  }

  @Test
  void userGradlePropertiesWorkWithoutEnvironmentAndRefreshAfterTokenRotation() throws Exception {
    Path userHome = isolatedUserHome();
    Path properties = userHome.resolve("gradle.properties");
    writeConnection(properties, CREDENTIAL);
    String[] args = {
      "jiraValidateRemote",
      "--connection=test",
      "--ticket-profile=bug",
      "--field=summary=Synthetic",
      "--configuration-cache",
      "--gradle-user-home",
      userHome.toString()
    };
    var environment = new HashMap<>(System.getenv());
    environment.remove("JIRA_TEST_URL");
    environment.remove("JIRA_TEST_EMAIL");
    environment.remove("JIRA_TEST_API_TOKEN");
    var testRunner = runner(CREDENTIAL, args).withEnvironment(environment);
    var first = testRunner.build();
    assertTrue(first.getOutput().contains("\"valid\":true"));
    assertEquals(basic(CREDENTIAL), authorizations.get(authorizations.size() - 1));
    assertFalse(first.getOutput().contains(CREDENTIAL));

    String rotated = "synthetic-properties-rotated-token";
    writeConnection(properties, rotated);
    var second = testRunner.build();
    assertEquals(basic(rotated), authorizations.get(authorizations.size() - 1));
    assertFalse(second.getOutput().contains(rotated));
    var third = testRunner.build();
    assertTrue(third.getOutput().contains("Reusing configuration cache."));
    assertEquals(basic(rotated), authorizations.get(authorizations.size() - 1));
    assertEquals(0, posts.get());
    try (var files = Files.walk(project.resolve(".gradle/configuration-cache"))) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(content.contains(CREDENTIAL));
        assertFalse(content.contains(rotated));
        assertFalse(content.contains(basic(rotated)));
      }
    }
  }

  @Test
  void userPropertiesOverrideEnvironmentAndCommandLineOverridesUserProperties() throws Exception {
    Path userHome = isolatedUserHome();
    writeConnection(userHome.resolve("gradle.properties"), "synthetic-user-properties-token");
    String[] args = {
      "jiraValidateRemote",
      "--connection=test",
      "--ticket-profile=bug",
      "--field=summary=Synthetic",
      "--configuration-cache",
      "--gradle-user-home",
      userHome.toString()
    };
    runner(CREDENTIAL, args).build();
    assertEquals(
        basic("synthetic-user-properties-token"), authorizations.get(authorizations.size() - 1));
    var overridden = new ArrayList<>(List.of(args));
    overridden.add("-Pjira.test.token=synthetic-command-line-token");
    runner(CREDENTIAL, overridden.toArray(String[]::new)).build();
    assertEquals(
        basic("synthetic-command-line-token"), authorizations.get(authorizations.size() - 1));
    overridden.set(overridden.size() - 1, "-Pjira.test.token=");
    int beforeFailure = requests.get();
    var failed = runner(CREDENTIAL, overridden.toArray(String[]::new)).buildAndFail();
    assertTrue(failed.getOutput().contains("INVALID_INPUT"));
    assertFalse(failed.getOutput().contains(CREDENTIAL));
    assertEquals(beforeFailure, requests.get());
  }

  private void writeConnection(Path file, String token) throws Exception {
    Files.writeString(
        file,
        "jira.test.url="
            + baseUrl
            + "\njira.test.email=tester@example.invalid\njira.test.token="
            + token
            + "\n");
  }

  @Test
  void repeatedInputUpdatesOrCreatesOnlyAfterAChoiceAndDefaultCancellationIsCacheCompatible()
      throws Exception {
    Path input = project.resolve("ticket.json");
    Files.writeString(input, "{\"summary\":\"First\"}");
    String[] args = {
      "jiraCreate",
      "--connection=test",
      "--ticket-profile=bug",
      "--input=ticket.json",
      "--configuration-cache"
    };
    runner(CREDENTIAL, args).build();
    assertEquals(1, posts.get());
    Files.writeString(input, "{\"summary\":\"Edited\"}");
    var updating = new ArrayList<>(List.of(args));
    updating.add("--on-existing=update");
    var updated = runner(CREDENTIAL, updating.toArray(String[]::new)).build();
    assertTrue(updated.getOutput().contains("APP-42"));
    assertEquals(Map.of("fields", Map.of("summary", "Edited")), updatedPayload);
    assertEquals(1, posts.get());
    assertEquals(1, updates.get());
    var cancelled = runner(CREDENTIAL, args).buildAndFail();
    assertTrue(cancelled.getOutput().contains("CANCELLED"));
    assertTrue(cancelled.getOutput().contains("APP-42"));
    var cancelledAgain = runner(CREDENTIAL, args).buildAndFail();
    assertTrue(cancelledAgain.getOutput().contains("Reusing configuration cache."));
    assertEquals(1, posts.get());
    assertEquals(1, updates.get());
    updating.set(updating.size() - 1, "--on-existing=create");
    runner(CREDENTIAL, updating.toArray(String[]::new)).build();
    assertEquals(2, posts.get());
  }

  @Test
  void pullPreviewsConfirmsBacksUpAndRefreshesValuesWithConfigurationCache() throws Exception {
    Path input = project.resolve("ticket.json");
    Files.writeString(input, "{\"summary\":\"Local\"}");
    runner(
            CREDENTIAL,
            "jiraCreate",
            "--connection=test",
            "--ticket-profile=bug",
            "--input=ticket.json",
            "--configuration-cache")
        .build();
    String[] args = {
      "jiraPull",
      "--connection=test",
      "--ticket-profile=bug",
      "--input=ticket.json",
      "--configuration-cache"
    };
    var previewArgs = new ArrayList<>(List.of(args));
    previewArgs.add("--preview");
    var preview = runner(CREDENTIAL, previewArgs.toArray(String[]::new)).build();
    assertTrue(preview.getOutput().contains("changedFields"));
    assertTrue(preview.getOutput().contains("summary"));
    assertFalse(preview.getOutput().contains(remoteSummary));
    assertTrue(Files.readString(input).contains("Local"));
    assertTrue(runner(CREDENTIAL, args).buildAndFail().getOutput().contains("CANCELLED"));
    assertTrue(Files.readString(input).contains("Local"));
    previewArgs.set(previewArgs.size() - 1, "--overwrite");
    var first = runner(CREDENTIAL, previewArgs.toArray(String[]::new)).build();
    assertTrue(first.getOutput().contains("backup"));
    assertTrue(Files.readString(input).contains(remoteSummary));
    remoteSummary = "Changed again remotely";
    var second = runner(CREDENTIAL, previewArgs.toArray(String[]::new)).build();
    assertTrue(second.getOutput().contains("Reusing configuration cache."));
    assertTrue(Files.readString(input).contains(remoteSummary));
    try (var files = Files.list(project.resolve(".gradle/jira-mark/publications/backups"))) {
      assertEquals(2, files.count());
    }
    assertEquals(1, posts.get());
    assertEquals(0, updates.get());
    runner(
            CREDENTIAL,
            "jiraCreate",
            "--connection=test",
            "--ticket-profile=bug",
            "--input=ticket.json",
            "--on-existing=update",
            "--configuration-cache")
        .build();
    assertEquals(1, posts.get());
    assertEquals(1, updates.get());
  }

  private static Path isolatedUserHome() throws Exception {
    // Gradle daemons retain cache locks on Windows. Keep their user homes outside JUnit fixtures,
    // just like TestKit's own persistent user home.
    return Files.createTempDirectory("jira-mark-test-user-home-");
  }

  @Test
  void repositoryDefaultsAndCommandLineOverridesWorkWithConfigurationCache() throws Exception {
    Files.writeString(
        project.resolve("build.gradle"),
        """
        plugins { id 'dev.vspex.jira-mark' }
        jira { connection = 'test' }
        tasks.named('jiraValidateRemote') {
          profile = 'bug'
          inputFile = layout.projectDirectory.file('ticket.json')
          fields = ['summary=Repository default']
        }
        tasks.named('jiraImportProfile') {
          profile = 'imported'
          issue = 'APP-1'
          outputFile = layout.projectDirectory.file('jira/profile-drafts/imported.md')
          reuseDefaults = []
        }
        """);
    // An empty file value is satisfied by the configured field override. CLI replacement with an
    // empty summary must fail instead of falling back to either configured or input values.
    Files.writeString(project.resolve("ticket.json"), "{\"summary\":\"\"}");
    var first = runner(CREDENTIAL, "jiraValidateRemote", "--configuration-cache").build();
    assertTrue(first.getOutput().contains("\"valid\":true"));
    var second = runner(CREDENTIAL, "jiraValidateRemote", "--configuration-cache").build();
    assertTrue(second.getOutput().contains("Reusing configuration cache."));
    var failed =
        runner(CREDENTIAL, "jiraValidateRemote", "--field=summary=", "--configuration-cache")
            .buildAndFail();
    assertTrue(failed.getOutput().contains("INVALID_INPUT"));
    var overridden =
        runner(CREDENTIAL, "jiraValidateRemote", "--connection=missing", "--configuration-cache")
            .buildAndFail();
    assertTrue(overridden.getOutput().contains("Selected connection URL is not configured"));
    runner(CREDENTIAL, "jiraImportProfile", "--configuration-cache").build();
    assertTrue(Files.exists(project.resolve("jira/profile-drafts/imported.md")));
    assertEquals(0, posts.get());
  }
}
