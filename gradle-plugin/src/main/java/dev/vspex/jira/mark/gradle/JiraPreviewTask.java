package dev.vspex.jira.mark.gradle;

import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.profile.DraftResolver;
import dev.vspex.jira.mark.profile.ProfileLoader;
import dev.vspex.jira.mark.profile.ProfileRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Writes an effective ticket draft. This is not yet a Jira REST payload. */
@DisableCachingByDefault(because = "Ticket drafts may contain private issue details")
public abstract class JiraPreviewTask extends DefaultTask {
  /** Creates an offline preview task with properties managed by Gradle. */
  public JiraPreviewTask() {}

  /** {@return the directory containing active Markdown profiles} */
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getProfilesDir();

  /** {@return the exact profile name to resolve} */
  @Input
  @Option(option = "ticket-profile", description = "Exact profile name, for example backend-bug")
  public abstract Property<String> getProfile();

  /** {@return ordered field overrides in {@code alias=value} form} */
  @Input
  public abstract ListProperty<String> getFields();

  /**
   * Sets command-line field overrides.
   *
   * @param fields ordered overrides; the last value for an alias wins
   */
  @Option(
      option = "field",
      description = "alias=value; repeat for multiple fields (arrays/objects use JSON)")
  public void setFieldArguments(List<String> fields) {
    getFields().set(fields);
  }

  /** {@return the optional YAML or JSON file of values keyed by field alias} */
  @Optional
  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  @Option(
      option = "input",
      description = "YAML or JSON file containing field alias-to-value mappings")
  public abstract RegularFileProperty getInputFile();

  /** {@return the destination for the resolved JSON draft} */
  @OutputFile
  public abstract RegularFileProperty getOutputFile();

  /**
   * Resolves the selected profile and writes an offline draft.
   *
   * @throws IOException if profile or input files cannot be read or the draft cannot be written
   */
  @TaskAction
  public void preview() throws IOException {
    var profile =
        ProfileRepository.select(getProfilesDir().get().getAsFile().toPath(), getProfile().get());
    Map<String, Object> input =
        getInputFile().isPresent()
            ? ProfileLoader.readInput(getInputFile().get().getAsFile().toPath())
            : Map.of();
    var draft = DraftResolver.resolve(profile, input, getFields().get());
    Path output = getOutputFile().get().getAsFile().toPath();
    Files.createDirectories(output.getParent());
    Files.writeString(output, Json.write(draft) + "\n");
    getLogger().lifecycle("Jira draft written to {}", output);
  }
}
