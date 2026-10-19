package dev.vspex.jira.mark.remote;

/** Safe workflow errors. Messages must never include credentials or remote response bodies. */
public final class JiraFailure extends RuntimeException {
  public enum Code {
    INVALID_INPUT(2),
    AUTHENTICATION(3),
    PERMISSION_DENIED(4),
    REMOTE_VALIDATION(5),
    REMOTE_UNAVAILABLE(6),
    UNCERTAIN_CREATION(7),
    INVALID_RESPONSE(8),
    CANCELLED(9);

    private final int exitCode;

    Code(int exitCode) {
      this.exitCode = exitCode;
    }

    public int exitCode() {
      return exitCode;
    }
  }

  private final Code code;

  public JiraFailure(Code code, String safeMessage) {
    super(safeMessage);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
