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
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getProfilesDir();

  @Input
  @Option(option = "ticket-profile", description = "Exact profile name, for example backend-bug")
  public abstract Property<String> getProfile();

  @Input
  public abstract ListProperty<String> getFields();

  @Option(
      option = "field",
      description = "alias=value; repeat for multiple fields (arrays/objects use JSON)")
  public void setFieldArguments(List<String> fields) {
    getFields().set(fields);
  }

  @Optional
  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  @Option(
      option = "input",
      description = "YAML or JSON file containing field alias-to-value mappings")
  public abstract RegularFileProperty getInputFile();

  @OutputFile
  public abstract RegularFileProperty getOutputFile();

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
