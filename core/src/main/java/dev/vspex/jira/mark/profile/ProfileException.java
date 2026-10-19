package dev.vspex.jira.mark.profile;

/** An invalid profile or ticket input. Messages must not include field values. */
public final class ProfileException extends IllegalArgumentException {
  public ProfileException(String message) {
    super(message);
  }
}
