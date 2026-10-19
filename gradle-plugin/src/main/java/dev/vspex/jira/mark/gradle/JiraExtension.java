package dev.vspex.jira.mark.gradle;

import org.gradle.api.file.DirectoryProperty;

/** Repository-owned Jira profile location. Connection configuration will accompany the client. */
public abstract class JiraExtension {
  public abstract DirectoryProperty getProfilesDir();
}
