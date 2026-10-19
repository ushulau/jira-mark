package dev.vspex.jira.mark.gradle;

import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Independent Jira plugin; does not apply Java or attach remote operations to the build. */
public final class JiraMarkPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    JiraExtension extension = project.getExtensions().create("jira", JiraExtension.class);
    extension
        .getProfilesDir()
        .convention(project.getLayout().getProjectDirectory().dir("jira/profiles"));
    project
        .getTasks()
        .register(
            "jiraProfiles",
            JiraProfilesTask.class,
            task -> {
              task.setGroup("jira");
              task.setDescription("Lists and validates Markdown Jira profiles offline.");
              task.getProfilesDir().convention(extension.getProfilesDir());
            });
    project
        .getTasks()
        .register(
            "jiraValidateProfiles",
            JiraValidateProfilesTask.class,
            task -> {
              task.setGroup("jira");
              task.setDescription("Validates Markdown Jira profile definitions offline.");
              task.getProfilesDir().convention(extension.getProfilesDir());
            });
    project
        .getTasks()
        .register(
            "jiraPreview",
            JiraPreviewTask.class,
            task -> {
              task.setGroup("jira");
              task.setDescription(
                  "Resolves a profile and field values into an offline ticket draft.");
              task.getProfilesDir().convention(extension.getProfilesDir());
              task.getFields().convention(List.of());
              task.getOutputFile()
                  .convention(project.getLayout().getBuildDirectory().file("jira/preview.json"));
            });
    registerRemote(
        project,
        extension,
        "jiraValidateRemote",
        "validate",
        "Validates ticket values against current Jira Cloud metadata.");
    registerRemote(
        project,
        extension,
        "jiraImportProfile",
        "import",
        "Imports a non-overwriting draft outside active profiles.");
    registerRemote(
        project,
        extension,
        "jiraCreate",
        "create",
        "Explicitly creates one Jira Cloud issue after validation.");
  }

  private static void registerRemote(
      Project project, JiraExtension extension, String name, String operation, String description) {
    project
        .getTasks()
        .register(
            name,
            JiraRemoteTask.class,
            task -> {
              task.setGroup("jira");
              task.setDescription(description);
              task.getOperation().set(operation);
              task.getOperation().disallowChanges();
              task.getProfilesDir().convention(extension.getProfilesDir());
            });
  }
}
