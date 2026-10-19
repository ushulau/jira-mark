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
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Explicit remote operations; credentials are resolved at execution and never task properties. */
@DisableCachingByDefault(because = "Remote operations must run only on explicit invocation")
public abstract class JiraRemoteTask extends DefaultTask {
  public JiraRemoteTask() {
    doNotTrackState(
        "Remote state and credentials must not be captured in Gradle execution history");
    getFields().convention(List.of());
    getReuseDefaults().convention(List.of());
  }

  @Input
  public abstract Property<String> getOperation();

  @Internal
  public abstract DirectoryProperty getProfilesDir();

  @Input
  @Option(option = "connection", description = "Explicit JIRA_<NAME> environment connection")
  public abstract Property<String> getConnection();

  @Input
  @Option(option = "ticket-profile", description = "Exact profile name")
  public abstract Property<String> getProfile();

  @Input
  public abstract ListProperty<String> getFields();

  @Option(option = "field", description = "alias=value; repeatable")
  public void setFieldArguments(List<String> fields) {
    getFields().set(fields);
  }

  @Internal
  @Option(option = "input", description = "YAML/JSON alias values")
  public abstract RegularFileProperty getInputFile();

  @Optional
  @Input
  @Option(option = "issue", description = "Import source issue key")
  public abstract Property<String> getIssue();

  @Internal
  @Option(option = "output", description = "Import draft file outside active profiles")
  public abstract RegularFileProperty getOutputFile();

  @Input
  public abstract ListProperty<String> getReuseDefaults();

  @Option(option = "reuse-default", description = "Import only: supported Jira field IDs to reuse")
  public void setReuseDefaultArguments(List<String> fields) {
    getReuseDefaults().set(fields);
  }

  @TaskAction
  public void executeRemote() {
    try {
      String operation = getOperation().get();
      if (!List.of("import", "validate", "create").contains(operation)) {
        throw new ProfileException("Invalid remote operation");
      }
      boolean importing = operation.equals("import");
      if (importing && (getInputFile().isPresent() || !getFields().get().isEmpty())) {
        throw new ProfileException("Import does not accept ticket input or field values");
      }
      if (!importing
          && (getIssue().isPresent()
              || getOutputFile().isPresent()
              || !getReuseDefaults().get().isEmpty())) {
        throw new ProfileException("Issue, output, and reuse-default options require import");
      }
      var gateway = new CloudJiraGateway(CloudConnection.fromEnvironment(getConnection().get()));
      Path profiles = getProfilesDir().get().getAsFile().toPath();
      if (importing) {
        if (!getIssue().isPresent() || !getOutputFile().isPresent()) {
          throw new ProfileException("Import requires --issue and --output");
        }
        var result =
            new ProfileImporter(gateway)
                .importProfile(
                    getIssue().get(),
                    getProfile().get(),
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
      var profile = ProfileRepository.select(profiles, getProfile().get());
      var input =
          getInputFile().isPresent()
              ? ProfileLoader.readInput(getInputFile().get().getAsFile().toPath())
              : Map.<String, Object>of();
      var workflow = new JiraWorkflow(gateway, new CloudFieldEncoder());
      if (operation.equals("validate")) {
        workflow.validate(profile, input, getFields().get());
        getLogger().lifecycle(Json.write(Map.of("valid", true, "profile", profile.name())));
      } else {
        var created = workflow.create(profile, input, getFields().get());
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
