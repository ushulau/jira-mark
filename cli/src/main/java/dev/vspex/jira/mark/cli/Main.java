package dev.vspex.jira.mark.cli;

import dev.vspex.jira.mark.cloud.CloudConnection;
import dev.vspex.jira.mark.cloud.CloudFieldDecoder;
import dev.vspex.jira.mark.cloud.CloudFieldEncoder;
import dev.vspex.jira.mark.cloud.CloudJiraGateway;
import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.profile.DraftResolver;
import dev.vspex.jira.mark.profile.ProfileException;
import dev.vspex.jira.mark.profile.ProfileLoader;
import dev.vspex.jira.mark.profile.ProfileRepository;
import dev.vspex.jira.mark.remote.InteractiveInput;
import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraGateway;
import dev.vspex.jira.mark.remote.JiraWorkflow;
import dev.vspex.jira.mark.remote.ProfileImporter;
import dev.vspex.jira.mark.remote.TicketPublisher;
import dev.vspex.jira.mark.remote.TicketPuller;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** JSON stdout for automation; safe JSON errors and interactive prompts on stderr. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    return run(
        args,
        out,
        err,
        name -> new CloudJiraGateway(CloudConnection.fromEnvironment(name)),
        System.console() == null ? null : new BufferedReader(System.console().reader()));
  }

  static int run(
      String[] args,
      PrintStream out,
      PrintStream err,
      Function<String, JiraGateway> connections,
      BufferedReader reader) {
    if (args.length == 0 || args[0].equals("--help")) {
      out.println(
          """
          Usage: jira-mark <profiles|validate|preview|validate-remote|import|create|pull> [options]
            --profiles-dir DIR     default: jira/profiles
            --profile NAME         exact profile name; optional for pull's standard fields
            --input FILE           YAML/JSON mapping; pull creates missing files with --issue
            --field ALIAS=VALUE    repeatable; arrays/objects use JSON
            --connection NAME      required for remote commands; JIRA_<NAME>_URL/EMAIL/API_TOKEN
                                   JIRA_<NAME>_TYPE=cloud (default) or data-center (PAT, no email)
            --issue KEY            import source, or explicit pull target when no recorded issue exists
            --output FILE          import destination; default: jira/profile-drafts/<profile>.md
            --reuse-default ID     import only; explicitly reuse a supported field (repeatable)
            --interactive          preview/validate-remote/create only; terminal required; :cancel aborts
            --on-existing ACTION   create only: ask (default), create, update, or cancel
            --history-dir DIR      create/pull: default .gradle/jira-mark/publications
            --dry-run              pull only: show changed field names without writing
            --overwrite            pull only: approve replacing local values without prompting

          profiles, validate, and preview are offline. Only create submits an issue.
          import never overwrites or activates a profile. Descriptions are literal text, not Markdown rendering.
          """);
      return 0;
    }
    try {
      String command = args[0];
      if (!List.of("profiles", "validate", "preview", "validate-remote", "import", "create", "pull")
          .contains(command)) {
        throw new ProfileException("Unknown command; use --help");
      }
      Path directory = Path.of("jira/profiles");
      String profile = null, connection = null, issue = null;
      Path inputFile = null, output = null;
      Path historyDirectory = Path.of(".gradle/jira-mark/publications");
      String onExisting = "ask";
      var fields = new ArrayList<String>();
      var defaults = new HashSet<String>();
      var supplied = new HashSet<String>();
      boolean interactive = false;
      boolean dryRun = false, overwrite = false;
      for (int i = 1; i < args.length; i++) {
        String option = args[i];
        supplied.add(option);
        if (option.equals("--interactive")) {
          interactive = true;
          continue;
        }
        if (option.equals("--dry-run")) {
          dryRun = true;
          continue;
        }
        if (option.equals("--overwrite")) {
          overwrite = true;
          continue;
        }
        if (++i >= args.length) {
          throw new ProfileException("Missing option value");
        }
        switch (option) {
          case "--profiles-dir" -> directory = Path.of(args[i]);
          case "--profile" -> profile = args[i];
          case "--input" -> inputFile = Path.of(args[i]);
          case "--field" -> fields.add(args[i]);
          case "--connection" -> connection = args[i];
          case "--issue" -> issue = args[i];
          case "--output" -> output = Path.of(args[i]);
          case "--reuse-default" -> defaults.add(args[i]);
          case "--on-existing" -> onExisting = args[i];
          case "--history-dir" -> historyDirectory = Path.of(args[i]);
          default -> throw new ProfileException("Unknown option; use --help");
        }
      }
      Set<String> allowed =
          switch (command) {
            case "profiles", "validate" -> Set.of("--profiles-dir");
            case "preview" ->
                Set.of("--profiles-dir", "--profile", "--input", "--field", "--interactive");
            case "import" ->
                Set.of(
                    "--profiles-dir",
                    "--profile",
                    "--connection",
                    "--issue",
                    "--output",
                    "--reuse-default");
            case "create" ->
                Set.of(
                    "--profiles-dir",
                    "--profile",
                    "--connection",
                    "--input",
                    "--field",
                    "--interactive",
                    "--on-existing",
                    "--history-dir");
            case "pull" ->
                Set.of(
                    "--profiles-dir",
                    "--profile",
                    "--connection",
                    "--input",
                    "--issue",
                    "--history-dir",
                    "--dry-run",
                    "--overwrite");
            default ->
                Set.of(
                    "--profiles-dir",
                    "--profile",
                    "--connection",
                    "--input",
                    "--field",
                    "--interactive");
          };
      if (!allowed.containsAll(supplied)) {
        throw new ProfileException("Options do not apply to this command; use --help");
      }
      boolean remote = List.of("validate-remote", "import", "create", "pull").contains(command);
      if (remote && (connection == null || connection.isBlank())) {
        throw new ProfileException("--connection is required");
      }
      if (!List.of("profiles", "validate", "pull").contains(command)
          && (profile == null || profile.isBlank())) {
        throw new ProfileException("--profile is required");
      }
      if (command.equals("profiles")) {
        out.println(Json.write(List.copyOf(ProfileRepository.load(directory).keySet())));
        return 0;
      }
      if (command.equals("validate")) {
        out.println(
            Json.write(
                Map.of("valid", true, "profiles", ProfileRepository.load(directory).size())));
        return 0;
      }
      if (command.equals("import")) {
        if (issue == null) {
          throw new ProfileException("--issue is required");
        }
        if (output == null) {
          if (!profile.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new ProfileException("Invalid profile name");
          }
          output = Path.of("jira/profile-drafts", profile + ".md");
        }
        var result =
            new ProfileImporter(connections.apply(connection))
                .importProfile(issue, profile, defaults, output, directory);
        out.println(
            Json.write(
                Map.of("output", result.output().toString(), "reviewNotes", result.reviewNotes())));
        return 0;
      }
      var selected =
          command.equals("pull") && (profile == null || profile.isBlank())
              ? null
              : ProfileRepository.select(directory, profile);
      if (command.equals("pull")) {
        if (inputFile == null)
          throw new ProfileException(
              "Pull requires --input pointing to a ticket file; missing files are created with --issue");
        var result =
            new TicketPuller(connections.apply(connection), new CloudFieldDecoder())
                .pull(
                    historyDirectory,
                    connection,
                    directory,
                    selected,
                    inputFile,
                    issue,
                    dryRun,
                    overwrite,
                    preview -> InteractiveInput.confirmPull(preview, reader, err));
        out.println(Json.write(result.description()));
        return 0;
      }
      Map<String, Object> input = inputFile == null ? Map.of() : ProfileLoader.readInput(inputFile);
      JiraGateway gateway = remote ? connections.apply(connection) : null;
      if (interactive) {
        input =
            InteractiveInput.complete(
                selected,
                input,
                fields,
                remote ? gateway.metadata(selected.project(), selected.issueType()) : null,
                reader,
                err);
        fields.clear();
      }
      if (command.equals("preview")) {
        out.println(Json.write(DraftResolver.resolve(selected, input, fields)));
      } else {
        var workflow = new JiraWorkflow(gateway, new CloudFieldEncoder());
        if (command.equals("validate-remote")) {
          workflow.validate(selected, input, fields);
          out.println(Json.write(Map.of("valid", true, "profile", profile)));
        } else {
          var created =
              new TicketPublisher(gateway, new CloudFieldEncoder())
                  .publish(
                      historyDirectory,
                      connection,
                      directory,
                      selected,
                      inputFile,
                      input,
                      fields,
                      onExisting,
                      key -> InteractiveInput.chooseExisting(key, reader, err));
          out.println(Json.write(Map.of("key", created.key(), "url", created.url().toString())));
        }
      }
      return 0;
    } catch (JiraFailure failure) {
      err.println(
          Json.write(Map.of("error", failure.code().name(), "message", failure.getMessage())));
      return failure.code().exitCode();
    } catch (IOException | IllegalArgumentException failure) {
      String message =
          failure instanceof ProfileException
              ? failure.getMessage()
              : "Cannot read the requested path or input";
      err.println(Json.write(Map.of("error", "INVALID_INPUT", "message", message)));
      return 2;
    }
  }
}
