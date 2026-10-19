package dev.vspex.jira.mark.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads all Markdown profiles in a directory, rejecting duplicate names. */
public final class ProfileRepository {
  private ProfileRepository() {}

  public static Map<String, Profile> load(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      throw new ProfileException("Profile directory does not exist: " + directory);
    }
    Map<String, Profile> profiles = new LinkedHashMap<>();
    try (var files = Files.list(directory)) {
      for (Path file :
          files
              .filter(Files::isRegularFile)
              .filter(p -> p.toString().endsWith(".md"))
              .sorted()
              .toList()) {
        Profile profile = ProfileLoader.load(file);
        if (profiles.putIfAbsent(profile.name(), profile) != null) {
          throw new ProfileException("Duplicate profile name: " + profile.name());
        }
      }
    }
    if (profiles.isEmpty()) {
      throw new ProfileException("No Markdown profiles found in " + directory);
    }
    return Collections.unmodifiableMap(profiles);
  }

  public static Profile select(Path directory, String name) throws IOException {
    Map<String, Profile> profiles = load(directory);
    Profile profile = profiles.get(name);
    if (profile == null) {
      throw new ProfileException(
          "Unknown profile '" + name + "'. Available: " + String.join(", ", profiles.keySet()));
    }
    return profile;
  }
}
