package dev.vspex.jira.mark.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliDistributionTest {
  @TempDir Path directory;

  @Test
  void installedCliResolvesOfflineValidatesImportsAndExplicitlyCreates() throws Exception {
    Path profiles = directory.resolve("jira/profiles");
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
        Local guidance only.
        """);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var posts = new AtomicInteger();
    var requests = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          int status = 200;
          String response;
          String path = exchange.getRequestURI().getPath();
          if (exchange.getRequestMethod().equals("POST")) {
            posts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            status = 201;
            response = "{\"key\":\"APP-42\"}";
          } else if (path.contains("/project/")) {
            response = "{\"id\":\"100\"}";
          } else if (path.endsWith("/issuetypes")) {
            response =
                "{\"startAt\":0,\"total\":1,\"issueTypes\":[{\"id\":\"2\",\"name\":\"Bug\"}]}";
          } else if (path.endsWith("/issuetypes/2")) {
            response =
                "{\"startAt\":0,\"total\":1,\"fields\":[{\"fieldId\":\"summary\",\"schema\":{\"type\":\"string\"},\"required\":true,\"operations\":[\"set\"]}]}";
          } else if (path.endsWith("/issue/APP-1")) {
            response =
                "{\"key\":\"APP-1\",\"fields\":{\"project\":{\"id\":\"100\"},\"issuetype\":{\"id\":\"2\"}}}";
          } else {
            response = "{}";
            status = 404;
          }
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var preview = run(url, "preview", "--profile", "bug", "--field", "summary=Synthetic");
      assertEquals(0, preview.status());
      assertTrue(preview.output().contains("Synthetic"));
      assertEquals(0, requests.get());
      var validate =
          run(
              url,
              "validate-remote",
              "--connection",
              "test",
              "--profile",
              "bug",
              "--field",
              "summary=Synthetic");
      assertEquals(0, validate.status(), validate.error());
      assertTrue(validate.output().contains("\"valid\":true"));
      assertEquals(0, posts.get());
      var imported =
          run(url, "import", "--connection", "test", "--profile", "imported", "--issue", "APP-1");
      assertEquals(0, imported.status(), imported.error());
      assertTrue(Files.exists(directory.resolve("jira/profile-drafts/imported.md")));
      assertFalse(Files.exists(profiles.resolve("imported.md")));
      assertEquals(0, posts.get());
      var created =
          run(
              url,
              "create",
              "--connection",
              "test",
              "--profile",
              "bug",
              "--field",
              "summary=Synthetic");
      assertEquals(0, created.status(), created.error());
      assertTrue(created.output().contains("\"key\":\"APP-42\""));
      assertEquals(1, posts.get());
      assertFalse(created.output().contains("synthetic-distribution-credential"));
      assertEquals("", created.error());
    } finally {
      server.stop(0);
    }
  }

  private Result run(String url, String... args) throws Exception {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    var command =
        new ArrayList<String>(
            List.of(
                executable,
                "-cp",
                Path.of(System.getProperty("jiraMark.cliInstall"), "lib")
                    + java.io.File.separator
                    + "*",
                "dev.vspex.jira.mark.cli.Main"));
    command.addAll(List.of(args));
    var builder = new ProcessBuilder(command).directory(directory.toFile());
    builder.environment().put("JIRA_TEST_URL", url);
    builder.environment().put("JIRA_TEST_EMAIL", "tester@example.invalid");
    builder.environment().put("JIRA_TEST_API_TOKEN", "synthetic-distribution-credential");
    Path output = directory.resolve("stdout.txt");
    Path error = directory.resolve("stderr.txt");
    var process = builder.redirectOutput(output.toFile()).redirectError(error.toFile()).start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("Installed CLI timed out");
    }
    return new Result(process.exitValue(), Files.readString(output), Files.readString(error));
  }

  record Result(int status, String output, String error) {}
}
