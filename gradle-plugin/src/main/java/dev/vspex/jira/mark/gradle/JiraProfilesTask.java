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
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getProfilesDir();

  @TaskAction
  public void list() throws IOException {
    getLogger()
        .lifecycle(
            Json.write(
                java.util.List.copyOf(
                    ProfileRepository.load(getProfilesDir().get().getAsFile().toPath()).keySet())));
  }
}
