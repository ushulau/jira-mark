package dev.vspex.jira.mark.gradle;

import dev.vspex.jira.mark.cloud.CloudConnection;
import dev.vspex.jira.mark.cloud.CloudFieldEncoder;
import dev.vspex.jira.mark.cloud.CloudJiraGateway;
import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.profile.ProfileException;
import dev.vspex.jira.mark.profile.ProfileLoader;
import dev.vspex.jira.mark.profile.ProfileRepository;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraWorkflow;
import dev.vspex.jira.mark.remote.ProfileImporter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Explicit remote operations; credentials are resolved at execution and never task properties. */
@DisableCachingByDefault(because = "Remote operations must run only on explicit invocation")
public abstract class JiraRemoteTask extends DefaultTask {
  /** Creates a task that always executes and does not retain remote execution history. */
  public JiraRemoteTask() {
    doNotTrackState(
        "Remote state and credentials must not be captured in Gradle execution history");
    getFields().convention(List.of());
    getReuseDefaults().convention(List.of());
  }

  /**
   * {@return the plugin-selected operation: {@code import}, {@code validate}, or {@code create}}
   */
  @Input
  public abstract Property<String> getOperation();

  /** {@return the active profile directory, also used to protect imports from activation} */
  @Internal
  public abstract DirectoryProperty getProfilesDir();

  /** {@return the explicit connection name used to resolve credentials at execution} */
  @Optional
  @Input
  @Option(
      option = "connection",
      description = "Named Jira connection from Gradle properties or environment")
  public abstract Property<String> getConnection();

  /** {@return the factory for resolving Gradle properties during task execution} */
  @Inject
  protected abstract ProviderFactory getProviders();

  /** {@return the exact active profile name, or the new draft profile name when importing} */
  @Optional
  @Input
  @Option(
      option = "ticket-profile",
      description = "Exact profile name; for import, the name to assign to the new draft")
  public abstract Property<String> getProfile();

  /** {@return ordered field overrides for validation or creation} */
  @Input
  public abstract ListProperty<String> getFields();

  /**
   * Sets command-line field overrides.
   *
   * @param fields ordered {@code alias=value} overrides; the last value for an alias wins
   */
  @Option(option = "field", description = "alias=value; repeatable")
  public void setFieldArguments(List<String> fields) {
    getFields().set(fields);
  }

  /** {@return the optional YAML or JSON input for validation or creation} */
  @Internal
  @Option(option = "input", description = "YAML/JSON alias values")
  public abstract RegularFileProperty getInputFile();

  /** {@return the source issue key for import} */
  @Optional
  @Input
  @Option(option = "issue", description = "Import source issue key")
  public abstract Property<String> getIssue();

  /**
   * {@return the import destination outside active profiles; existing files are never overwritten}
   */
  @Internal
  @Option(option = "output", description = "Import draft file outside active profiles")
  public abstract RegularFileProperty getOutputFile();

  /** {@return the supported Jira field IDs whose values should become imported defaults} */
  @Input
  public abstract ListProperty<String> getReuseDefaults();

  /**
   * Selects source issue fields to reuse as defaults during import.
   *
   * @param fields supported Jira field IDs to copy
   */
  @Option(option = "reuse-default", description = "Import only: supported Jira field IDs to reuse")
  public void setReuseDefaultArguments(List<String> fields) {
    getReuseDefaults().set(fields);
  }

  /** Executes the explicitly selected remote operation using execution-time credentials. */
  @TaskAction
  public void executeRemote() {
    try {
      String operation = getOperation().get();
      if (!List.of("import", "validate", "create").contains(operation)) {
        throw new ProfileException("Invalid remote operation");
      }
      boolean importing = operation.equals("import");
      // Validate required options here so errors name the CLI option rather than the Java property.
      String profile = getProfile().getOrNull();
      if (profile == null || profile.isBlank()) {
        throw new ProfileException(
            (importing
                    ? "Import requires --ticket-profile=<name> for the new draft profile"
                    : "This operation requires --ticket-profile=<name>")
                + "; alternatively configure the task's profile in build.gradle");
      }
      String connection = getConnection().getOrNull();
      if (connection == null || connection.isBlank()) {
        throw new ProfileException(
            "Remote operations require --connection=<name> or a configured jira.connection in build.gradle");
      }
      if (importing && (!getIssue().isPresent() || !getOutputFile().isPresent())) {
        throw new ProfileException("Import requires --issue and --output");
      }
      if (importing && (getInputFile().isPresent() || !getFields().get().isEmpty())) {
        throw new ProfileException("Import does not accept ticket input or field values");
      }
      if (!importing
          && (getIssue().isPresent()
              || getOutputFile().isPresent()
              || !getReuseDefaults().get().isEmpty())) {
        throw new ProfileException("Issue, output, and reuse-default options require import");
      }
      var gateway =
          new CloudJiraGateway(
              CloudConnection.fromConfiguration(
                  connection,
                  key -> getProviders().gradleProperty(key).getOrNull(),
                  System::getenv));
      Path profiles = getProfilesDir().get().getAsFile().toPath();
      if (importing) {
        var result =
            new ProfileImporter(gateway)
                .importProfile(
                    getIssue().get(),
                    profile,
                    Set.copyOf(getReuseDefaults().get()),
                    getOutputFile().get().getAsFile().toPath(),
                    profiles);
        getLogger()
            .lifecycle(
                Json.write(
                    Map.of(
                        "output",
                        result.output().toString(),
                        "reviewNotes",
                        result.reviewNotes())));
        return;
      }
      var selectedProfile = ProfileRepository.select(profiles, profile);
      var input =
          getInputFile().isPresent()
              ? ProfileLoader.readInput(getInputFile().get().getAsFile().toPath())
              : Map.<String, Object>of();
      var workflow = new JiraWorkflow(gateway, new CloudFieldEncoder());
      if (operation.equals("validate")) {
        workflow.validate(selectedProfile, input, getFields().get());
        getLogger().lifecycle(Json.write(Map.of("valid", true, "profile", selectedProfile.name())));
      } else {
        var created = workflow.create(selectedProfile, input, getFields().get());
        getLogger()
            .lifecycle(Json.write(Map.of("key", created.key(), "url", created.url().toString())));
      }
    } catch (JiraFailure failure) {
      throw new GradleException(failure.code().name() + ": " + failure.getMessage());
    } catch (IOException | IllegalArgumentException failure) {
      throw new GradleException(
          "INVALID_INPUT: "
              + (failure instanceof ProfileException
                  ? failure.getMessage()
                  : "Cannot read the requested path or input"));
    }
  }
}
