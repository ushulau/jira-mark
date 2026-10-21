package dev.vspex.jira.mark.remote;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;

import dev.vspex.jira.mark.json.Json;
import dev.vspex.jira.mark.profile.Profile;
import dev.vspex.jira.mark.profile.ProfileException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Locked local issue bindings shared by publishing and pull operations. */
final class PublicationJournal implements AutoCloseable {
  private final Path record;
  private final FileChannel channel;
  private final FileLock lock;

  private PublicationJournal(Path record, FileChannel channel, FileLock lock) {
    this.record = record;
    this.channel = channel;
    this.lock = lock;
  }

  static PublicationJournal open(
      Path historyDirectory,
      JiraGateway gateway,
      String connection,
      Path profilesDirectory,
      Profile profile,
      Path inputFile,
      Map<String, Object> draft)
      throws IOException {
    String source =
        inputFile == null
            ? "content:" + digest(Json.write(canonical(draft)))
            : "file:" + inputFile.toRealPath();
    String identity =
        digest(
            Json.write(
                List.of(
                    gateway.publicationScope(),
                    connection.toLowerCase(java.util.Locale.ROOT),
                    profilesDirectory.toRealPath().toString(),
                    profile.name(),
                    profile.project(),
                    profile.issueType(),
                    source)));
    Files.createDirectories(historyDirectory);
    Path record = historyDirectory.resolve(identity + ".json");
    var channel =
        FileChannel.open(
            historyDirectory.resolve(identity + ".lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException conflict) {
        throw busy();
      }
      if (lock == null) throw busy();
      return new PublicationJournal(record, channel, lock);
    } catch (IOException | RuntimeException failure) {
      channel.close();
      throw failure;
    }
  }

  Entry confirmedEntry() throws IOException {
    Entry previous = read(record);
    if (previous != null && !previous.status().equals("confirmed")) {
      throw new JiraFailure(
          previous.status().equals("pending-update") ? UNCERTAIN_UPDATE : UNCERTAIN_CREATION,
          "A previous submission is unresolved. Inspect Jira and reconcile publication history "
              + record.getFileName()
              + " before continuing");
    }
    return previous;
  }

  void write(Entry entry) throws IOException {
    write(record, entry);
  }

  void clear() throws IOException {
    Files.deleteIfExists(record);
  }

  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }

  private static Entry read(Path file) throws IOException {
    if (!Files.exists(file)) return null;
    try {
      if (Files.size(file) > 4096) throw new IllegalArgumentException();
      var data = (Map<?, ?>) Json.parse(Files.readString(file));
      String status = (String) data.get("status");
      String key = (String) data.get("key");
      String project = (String) data.get("project");
      String issueType = (String) data.get("issueType");
      if (!Long.valueOf(1).equals(data.get("schemaVersion"))
          || !List.of("confirmed", "pending-create", "pending-update").contains(status)
          || key == null
          || !(status.equals("pending-create") && key.isEmpty()
              || key.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+"))
          || project == null
          || !project.matches("[A-Za-z0-9_-]+")
          || issueType == null
          || !issueType.matches("[A-Za-z0-9_-]+")) {
        throw new IllegalArgumentException();
      }
      return new Entry(status, key, project, issueType);
    } catch (RuntimeException invalid) {
      throw new ProfileException(
          "Publication history is invalid; inspect the local history before submitting");
    }
  }

  private static void write(Path file, Entry entry) throws IOException {
    Path temporary = Files.createTempFile(file.getParent(), "publication-", ".tmp");
    try {
      Files.writeString(
          temporary,
          Json.write(
                  Map.of(
                      "schemaVersion",
                      1,
                      "status",
                      entry.status(),
                      "key",
                      entry.key(),
                      "project",
                      entry.project(),
                      "issueType",
                      entry.issueType()))
              + "\n");
      try {
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((key, item) -> sorted.put((String) key, canonical(item)));
      return sorted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(PublicationJournal::canonical).toList();
    return value;
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static JiraFailure busy() {
    return new JiraFailure(
        INVALID_INPUT,
        "Another publication for this input is in progress; retry after it finishes");
  }

  record Entry(String status, String key, String project, String issueType) {}
}
