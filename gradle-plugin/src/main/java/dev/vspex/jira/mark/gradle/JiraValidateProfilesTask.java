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
  /** Creates an offline profile validation task. */
  public JiraValidateProfilesTask() {}

  /** {@return the directory containing active Markdown profiles} */
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getProfilesDir();

  /**
   * Validates all active profile definitions and logs their count.
   *
   * @throws IOException if the profile directory or its files cannot be read
   */
  @TaskAction
  public void validate() throws IOException {
    int count = ProfileRepository.load(getProfilesDir().get().getAsFile().toPath()).size();
    getLogger().lifecycle("Validated {} Jira profiles.", count);
  }
}
