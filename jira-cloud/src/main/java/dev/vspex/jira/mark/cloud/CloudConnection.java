package dev.vspex.jira.mark.cloud;

import dev.vspex.jira.mark.profile.ProfileException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Function;

/** Execution-time connection settings. Never store this object in a Gradle task property. */
public final class CloudConnection {
  private final URI baseUrl;
  private final String authorization;

  public CloudConnection(URI baseUrl, String email, String apiToken) {
    if (baseUrl == null
        || baseUrl.getHost() == null
        || baseUrl.getUserInfo() != null
        || baseUrl.getQuery() != null
        || baseUrl.getFragment() != null
        || !("https".equals(baseUrl.getScheme())
            || "http".equals(baseUrl.getScheme())
                && ("127.0.0.1".equals(baseUrl.getHost()) || "localhost".equals(baseUrl.getHost())))
        || !baseUrl.getPath().matches("(?:/[A-Za-z0-9._~-]+)*/?")) {
      throw new ProfileException(
          "Connection URL must be HTTPS without credentials, query, or fragment");
    }
    if (email == null
        || email.isBlank()
        || email.contains(":")
        || email.chars().anyMatch(Character::isISOControl)
        || apiToken == null
        || apiToken.isBlank()
        || apiToken.chars().anyMatch(Character::isISOControl)) {
      throw new ProfileException("Connection requires an account email and API token");
    }
    this.baseUrl = URI.create(baseUrl.toString().replaceAll("/+$", ""));
    this.authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
  }

  public static CloudConnection fromEnvironment(String name) {
    return fromEnvironment(name, System::getenv);
  }

  public static CloudConnection fromEnvironment(String name, Function<String, String> environment) {
    if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*")) {
      throw new ProfileException(
          "An explicit connection name is required (letters, digits, underscores)");
    }
    String prefix = "JIRA_" + name.toUpperCase(java.util.Locale.ROOT) + "_";
    String url = environment.apply(prefix + "URL");
    if (url == null || url.isBlank()) {
      throw new ProfileException("Selected connection URL is not configured");
    }
    try {
      return new CloudConnection(
          URI.create(url),
          environment.apply(prefix + "EMAIL"),
          environment.apply(prefix + "API_TOKEN"));
    } catch (IllegalArgumentException failure) {
      throw new ProfileException(
          "Selected connection requires a valid URL, account email, and API token");
    }
  }

  public URI baseUrl() {
    return baseUrl;
  }

  String authorization() {
    return authorization;
  }

  @Override
  public String toString() {
    return "CloudConnection[redacted]";
  }
}
