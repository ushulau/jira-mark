package dev.vspex.jira.mark.gradle;

import org.gradle.api.file.DirectoryProperty;

/** Configures the location of repository-owned Jira profiles. */
public abstract class JiraExtension {
  /** Creates the extension with properties managed by Gradle. */
  public JiraExtension() {}

  /** {@return the profile directory, defaulting to {@code jira/profiles}} */
  public abstract DirectoryProperty getProfilesDir();
}
