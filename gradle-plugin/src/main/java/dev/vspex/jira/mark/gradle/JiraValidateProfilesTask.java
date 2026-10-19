package dev.vspex.jira.mark.gradle;

import dev.vspex.jira.mark.profile.ProfileRepository;
import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Validates profile definitions offline; missing per-ticket values are expected here. */
@DisableCachingByDefault(because = "Validation has no persistent output")
public abstract class JiraValidateProfilesTask extends DefaultTask {
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getProfilesDir();

  @TaskAction
  public void validate() throws IOException {
    int count = ProfileRepository.load(getProfilesDir().get().getAsFile().toPath()).size();
    getLogger().lifecycle("Validated {} Jira profiles.", count);
  }
}
