package dev.vspex.jira.mark.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/** Configures repository-owned Jira profiles and an optional default remote connection. */
public abstract class JiraExtension {
  /** Creates the extension with properties managed by Gradle. */
  public JiraExtension() {}

  /** {@return the profile directory, defaulting to {@code jira/profiles}} */
  public abstract DirectoryProperty getProfilesDir();

  /** {@return the default named connection for remote tasks, unset unless configured} */
  public abstract Property<String> getConnection();
}
