package dev.vspex.jira.mark.gradle;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JiraMarkPluginFunctionalTest {
  @TempDir Path project;

  @BeforeEach
  void prepare() throws Exception {
    Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
    Files.writeString(project.resolve("build.gradle"), "plugins { id 'dev.vspex.jira-mark' }\n");
    Path profiles = project.resolve("jira/profiles");
    Files.createDirectories(profiles);
    Files.writeString(
        profiles.resolve("bug.md"),
        """
        ---
        schemaVersion: 1
        profile: backend-bug
        project: APP
        issueType: Bug
        fields:
          summary:
            required: true
          labels:
            type: string-list
            default: [backend]
        ---
        # Guidance
        """);
  }

  private GradleRunner runner(String... arguments) {
    return GradleRunner.create()
        .withProjectDir(project.toFile())
        .withPluginClasspath()
        .withArguments(arguments);
  }

  @Test
  void previewWritesDraftAndReusesConfigurationCache() throws Exception {
    runner(
            "jiraPreview",
            "--ticket-profile=backend-bug",
            "--field=summary=CLI value",
            "--configuration-cache")
        .build();
    Path output = project.resolve("build/jira/preview.json");
    assertTrue(Files.readString(output).contains("CLI value"));
    assertTrue(Files.readString(output).contains("backend"));
    var result =
        runner(
                "jiraPreview",
                "--ticket-profile=backend-bug",
                "--field=summary=CLI value",
                "--configuration-cache")
            .build();
    assertTrue(result.getOutput().contains("Reusing configuration cache."));
  }

  @Test
  void listsProfilesAndFailsMissingValuesWithoutPrompting() {
    assertTrue(runner("jiraProfiles").build().getOutput().contains("[\"backend-bug\"]"));
    assertTrue(
        runner("jiraValidateProfiles").build().getOutput().contains("Validated 1 Jira profiles."));
    assertTrue(
        runner("jiraPreview", "--ticket-profile=backend-bug")
            .buildAndFail()
            .getOutput()
            .contains("Missing required fields: summary"));
  }

  @Test
  void fieldArgumentsOverrideFileInput() throws Exception {
    Files.writeString(project.resolve("ticket.json"), "{\"summary\":\"From file\"}");
    runner(
            "jiraPreview",
            "--ticket-profile=backend-bug",
            "--input=ticket.json",
            "--field=summary=Override",
            "--field=labels=[\"cli\"]")
        .build();
    String output = Files.readString(project.resolve("build/jira/preview.json"));
    assertTrue(output.contains("Override"));
    assertTrue(output.contains("[\"cli\"]"));
    assertFalse(output.contains("From file"));
  }
}
