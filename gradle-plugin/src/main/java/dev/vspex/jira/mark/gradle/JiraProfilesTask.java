package dev.vspex.jira.mark.gradle;

import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.profile.ProfileRepository;
import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Lists exact profile names after validating all profile definitions. */
@DisableCachingByDefault(because = "Produces console output only")
public abstract class JiraProfilesTask extends DefaultTask {
  /** Creates an offline profile listing task. */
  public JiraProfilesTask() {}

  /** {@return the directory containing active Markdown profiles} */
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getProfilesDir();

  /**
   * Validates the profiles and logs their exact names as JSON.
   *
   * @throws IOException if the profile directory or its files cannot be read
   */
  @TaskAction
  public void list() throws IOException {
    getLogger()
        .lifecycle(
            Json.write(
                java.util.List.copyOf(
                    ProfileRepository.load(getProfilesDir().get().getAsFile().toPath()).keySet())));
  }
}
