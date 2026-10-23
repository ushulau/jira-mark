package dev.vspex.jira.mark.remote;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;

import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.profile.DraftResolver;
import dev.vspex.jira.mark.profile.FieldDefinition;
import dev.vspex.jira.mark.profile.Profile;
import dev.vspex.jira.mark.profile.ProfileException;
import dev.vspex.jira.mark.profile.ProfileLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Pulls configured or standard issue fields into a ticket file, with approval before overwriting.
 */
public final class TicketPuller {
  private final JiraGateway gateway;
  private final JiraFieldDecoder decoder;

  public TicketPuller(JiraGateway gateway, JiraFieldDecoder decoder) {
    this.gateway = gateway;
    this.decoder = decoder;
  }

  public record Preview(String key, List<String> changedFields, boolean changesBinding) {}

  public record Result(
      String key, Path input, List<String> changedFields, boolean written, Path backup) {
    public Map<String, Object> description() {
      var result = new LinkedHashMap<String, Object>();
      result.put("key", key);
      result.put("input", input.toString());
      result.put("changedFields", changedFields);
      result.put("written", written);
      if (backup != null) result.put("backup", backup.toString());
      return result;
    }
  }

  public Result pull(
      Path historyDirectory,
      String connection,
      Path profilesDirectory,
      Profile profile,
      Path inputFile,
      String explicitIssue,
      boolean dryRun,
      boolean overwrite,
      Function<Preview, Boolean> confirm)
      throws IOException {
    if (inputFile == null)
      throw new ProfileException(
          "Pull requires --input pointing to a .json, .yaml, or .yml file; missing files are created with --issue");
    Path target = PublicationJournal.canonicalPath(inputFile);
    String name = target.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (!(name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml"))) {
      throw new ProfileException("Pull input must have a .json, .yaml, or .yml extension");
    }
    boolean exists = Files.exists(target);
    if (exists && (!Files.isRegularFile(target) || !Files.isReadable(target))) {
      throw new ProfileException("Pull input must be a readable regular file: " + target);
    }
    if (!exists && (explicitIssue == null || explicitIssue.isBlank())) {
      throw new ProfileException(
          "Pull input does not exist; use --issue=<key> to create it: " + target);
    }
    boolean standardFields = profile == null;
    if (standardFields) profile = standardProfile();
    try (var journal =
        PublicationJournal.open(
            historyDirectory, gateway, connection, profilesDirectory, profile, target, null)) {
      var previous = journal.confirmedEntry();
      String key = explicitIssue != null ? explicitIssue : previous == null ? null : previous.key();
      if (key == null || key.isBlank()) {
        throw new ProfileException(
            "No publication record exists for this input; use --issue=<key> to select an existing issue explicitly");
      }
      byte[] original = exists ? Files.readAllBytes(target) : null;
      Map<String, Object> local = exists ? ProfileLoader.readInput(target) : Map.of();
      if (standardFields && !profile.fields().keySet().containsAll(local.keySet())) {
        throw new ProfileException(
            "Input contains fields outside the standard pull mapping; select its exact profile with --ticket-profile (Gradle) or --profile (CLI)");
      }
      Set<String> fields =
          profile.fields().values().stream().map(field -> field.id()).collect(Collectors.toSet());
      var remote = gateway.readIssue(key, fields);
      if (!standardFields
              && (!(profile.project().equals(remote.projectId())
                      || profile.project().equals(remote.projectKey()))
                  || !(profile.issueType().equals(remote.issueTypeId())
                      || profile.issueType().equals(remote.issueTypeName())))
          || previous != null
              && (!previous.project().equals(remote.projectId())
                  || !previous.issueType().equals(remote.issueTypeId()))) {
        throw new JiraFailure(
            REMOTE_VALIDATION,
            "The issue's project or issue type does not match the profile and publication record");
      }
      var pulled = new LinkedHashMap<String, Object>(local);
      var changed = new ArrayList<String>();
      for (var entry : profile.fields().entrySet()) {
        String alias = entry.getKey();
        var field = entry.getValue();
        if (!remote.fields().containsKey(field.id())) {
          throw new JiraFailure(
              REMOTE_VALIDATION,
              "A requested Jira field is unavailable; the input file was not changed");
        }
        Object value = decoder.decode(field, remote.fields().get(field.id()), alias);
        pulled.put(alias, value);
        if (!local.containsKey(alias)
            || !Json.write(PublicationJournal.canonical(value))
                .equals(Json.write(PublicationJournal.canonical(local.get(alias)))))
          changed.add(alias);
      }
      // Validate the complete candidate before approval, backup, or writing. Explicit nulls
      // suppress defaults.
      DraftResolver.resolve(profile, pulled, List.of());
      var preview =
          new Preview(
              remote.key(),
              List.copyOf(changed),
              previous == null || !previous.key().equals(remote.key()));
      if (dryRun) return new Result(remote.key(), target, preview.changedFields(), false, null);
      if (exists
          && (!changed.isEmpty() || preview.changesBinding())
          && !overwrite
          && !Boolean.TRUE.equals(confirm.apply(preview))) {
        throw new JiraFailure(
            CANCELLED,
            "Pull cancelled; the input file was not changed. Use pull preview mode to review changes or --overwrite to apply Jira values");
      }
      requireUnchanged(target, original);
      if (changed.isEmpty()) {
        journal.write(
            new PublicationJournal.Entry(
                "confirmed", remote.key(), remote.projectId(), remote.issueTypeId()));
        return new Result(remote.key(), target, List.of(), false, null);
      }
      var options = new DumperOptions();
      options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      options.setAllowUnicode(true);
      options.setSplitLines(false);
      String text =
          name.endsWith(".json") ? pretty(pulled, 0) + "\n" : new Yaml(options).dump(pulled);
      Path backup = null;
      if (exists) {
        Path backups = historyDirectory.resolve("backups");
        Files.createDirectories(backups);
        backup =
            Files.createTempFile(backups, "ticket-", name.endsWith(".json") ? ".json" : ".yaml");
        Files.write(backup, original);
      }
      Files.createDirectories(target.getParent());
      Path temporary = Files.createTempFile(target.getParent(), ".jira-mark-pull-", ".tmp");
      try {
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        DraftResolver.resolve(profile, ProfileLoader.readInput(temporary), List.of());
        requireUnchanged(target, original);
        journal.write(
            new PublicationJournal.Entry(
                "confirmed", remote.key(), remote.projectId(), remote.issueTypeId()));
        if (!exists) {
          // A concurrent creator must never be overwritten, even with --overwrite.
          Files.move(temporary, target);
        } else
          try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
          } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
          }
      } catch (IOException failure) {
        try {
          if (previous == null) journal.clear();
          else journal.write(previous);
        } catch (IOException restoreFailure) {
          // The explicitly approved issue binding may remain if the filesystem is unavailable.
        }
        throw new ProfileException(
            backup == null
                ? "Pull could not create the input file; check its parent directory and permissions: "
                    + target
                : "Pull could not replace the input file; its original contents are backed up at "
                    + backup.toAbsolutePath());
      } finally {
        Files.deleteIfExists(temporary);
      }
      return new Result(
          remote.key(),
          target,
          preview.changedFields(),
          true,
          backup == null ? null : backup.toAbsolutePath());
    } catch (IOException failure) {
      throw new ProfileException(
          "Cannot read or write the pull input or publication history; check paths and permissions. Input: "
              + target
              + "; history: "
              + historyDirectory.toAbsolutePath());
    }
  }

  private static void requireUnchanged(Path target, byte[] original) throws IOException {
    if (original == null
        ? Files.exists(target)
        : !Files.exists(target) || !Arrays.equals(original, Files.readAllBytes(target))) {
      throw new ProfileException(
          "Input changed while pulling from Jira; no pull values were written. Retry after reviewing local edits");
    }
  }

  private static Profile standardProfile() {
    var fields = new LinkedHashMap<String, FieldDefinition>();
    fields.put("summary", new FieldDefinition("summary", "string", true, null, List.of()));
    fields.put("description", new FieldDefinition("description", "string", false, null, List.of()));
    fields.put("labels", new FieldDefinition("labels", "string-list", false, null, List.of()));
    fields.put("priority", new FieldDefinition("priority", "object", false, null, List.of()));
    fields.put("components", new FieldDefinition("components", "array", false, null, List.of()));
    return new Profile("jira-pull-standard", "any", "any", fields, "");
  }

  private static String pretty(Object value, int indent) {
    String padding = " ".repeat(indent + 2);
    String closing = " ".repeat(indent);
    if (value instanceof Map<?, ?> map && !map.isEmpty()) {
      return "{\n"
          + map.entrySet().stream()
              .map(
                  entry ->
                      padding
                          + Json.write(entry.getKey())
                          + ": "
                          + pretty(entry.getValue(), indent + 2))
              .collect(Collectors.joining(",\n"))
          + "\n"
          + closing
          + "}";
    }
    if (value instanceof List<?> list && !list.isEmpty()) {
      return "[\n"
          + list.stream()
              .map(item -> padding + pretty(item, indent + 2))
              .collect(Collectors.joining(",\n"))
          + "\n"
          + closing
          + "]";
    }
    return Json.write(value);
  }
}
