package dev.vspex.jira.mark.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraGateway;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
  @TempDir Path directory;
  int posts;
  int connections;
  ByteArrayOutputStream output;
  ByteArrayOutputStream error;

  @BeforeEach
  void prepare() throws Exception {
    output = new ByteArrayOutputStream();
    error = new ByteArrayOutputStream();
    Files.writeString(
        directory.resolve("bug.md"),
        """
        ---
        schemaVersion: 1
        profile: bug
        project: APP
        issueType: Bug
        fields:
          summary:
            required: true
          count:
            type: integer
            required: true
        ---
        Guidance must not become a field.
        """);
  }

  private JiraGateway gateway(String connection) {
    connections++;
    assertEquals("test", connection);
    return new JiraGateway() {
      public Metadata metadata(String project, String issueType) {
        return new Metadata(
            "100",
            "2",
            Map.of(
                "summary",
                new Field("summary", "string", true, false, true, "literal", List.of()),
                "count",
                new Field("count", "integer", true, false, true, "literal", List.of())));
      }

      public ExampleIssue example(String key) {
        throw new AssertionError("Import was not requested");
      }

      public CreatedIssue create(Map<String, Object> payload) {
        posts++;
        return new CreatedIssue("APP-42", URI.create("https://example.invalid/browse/APP-42"));
      }
    };
  }

  private int run(String command, String answers, String... options) {
    var args =
        new java.util.ArrayList<String>(
            List.of(command, "--profiles-dir", directory.toString(), "--profile", "bug"));
    args.addAll(List.of(options));
    return Main.run(
        args.toArray(String[]::new),
        new PrintStream(output),
        new PrintStream(error),
        this::gateway,
        answers == null ? null : new BufferedReader(new StringReader(answers)));
  }

  @Test
  void invalidCommandReturnsStructuredErrorAndNonzeroStatus() {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    int status = Main.run(new String[] {"create"}, new PrintStream(output), new PrintStream(error));
    assertEquals(2, status);
    assertEquals("", output.toString());
    assertInstanceOf(java.util.Map.class, Json.parse(error.toString()));
    assertTrue(error.toString().contains("INVALID_INPUT"));
  }

  @Test
  void previewRemainsOfflineAndRemoteValidationNeverCreates() {
    assertEquals(0, run("preview", null, "--field", "summary=Synthetic", "--field", "count=0"));
    assertEquals(0, connections);
    assertEquals(0, posts);
    assertEquals(
        0,
        run(
            "validate-remote",
            null,
            "--connection",
            "test",
            "--field",
            "summary=Synthetic",
            "--field",
            "count=0"));
    assertEquals(1, connections);
    assertEquals(0, posts);
  }

  @Test
  void createRequiresExplicitConnectionAndHasStructuredSuccess() {
    assertEquals(2, run("create", null, "--field", "summary=Synthetic", "--field", "count=0"));
    assertEquals(0, posts);
    error.reset();
    assertEquals(
        0,
        run(
            "create",
            null,
            "--connection",
            "test",
            "--field",
            "summary=Synthetic",
            "--field",
            "count=0"));
    assertEquals(1, posts);
    assertEquals("APP-42", ((Map<?, ?>) Json.parse(output.toString())).get("key"));
    assertEquals("", error.toString());
  }

  @Test
  void interactiveCompletionUsesTypesAndKeepsPromptsOffStdout() {
    assertEquals(0, run("preview", "Synthetic\ninvalid\n0\n", "--interactive"));
    var fields = (Map<?, ?>) ((Map<?, ?>) Json.parse(output.toString())).get("fields");
    assertEquals("Synthetic", fields.get("summary"));
    assertEquals(0L, fields.get("count"));
    assertTrue(error.toString().contains("Invalid value"));
    assertEquals(0, connections);
  }

  @Test
  void cancellationAndEofNeverSubmit() {
    assertEquals(9, run("create", ":cancel\n", "--connection", "test", "--interactive"));
    assertEquals(0, posts);
    assertEquals(9, run("create", "", "--connection", "test", "--interactive"));
    assertEquals(0, posts);
  }

  @Test
  void noninteractiveModeFailsRatherThanReadingStdin() {
    assertEquals(2, run("preview", null, "--interactive"));
    assertTrue(error.toString().contains("terminal"));
    assertEquals(0, posts);
  }

  @Test
  void remoteFailuresPreserveStableExitCodesAndSafeJson() {
    int status =
        Main.run(
            new String[] {
              "create",
              "--profiles-dir",
              directory.toString(),
              "--profile",
              "bug",
              "--connection",
              "test",
              "--field",
              "summary=Synthetic",
              "--field",
              "count=0"
            },
            new PrintStream(output),
            new PrintStream(error),
            name -> {
              throw new JiraFailure(JiraFailure.Code.AUTHENTICATION, "Jira authentication failed");
            },
            null);
    assertEquals(3, status);
    assertEquals("AUTHENTICATION", ((Map<?, ?>) Json.parse(error.toString())).get("error"));
    assertEquals("", output.toString());
  }
}
