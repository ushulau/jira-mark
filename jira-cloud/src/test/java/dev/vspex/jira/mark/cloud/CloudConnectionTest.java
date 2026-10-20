package dev.vspex.jira.mark.cloud;

import static org.junit.jupiter.api.Assertions.*;

import dev.vspex.jira.mark.profile.ProfileException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudConnectionTest {
  private static final Map<String, String> ENVIRONMENT =
      Map.of(
          "JIRA_WORK_URL", "https://environment.example.invalid",
          "JIRA_WORK_EMAIL", "environment@example.invalid",
          "JIRA_WORK_API_TOKEN", "synthetic-environment-token");

  @Test
  void propertiesOverrideEnvironmentForTheExplicitConnection() {
    var properties =
        Map.of(
            "jira.work.url", "https://properties.example.invalid",
            "jira.work.email", "properties@example.invalid",
            "jira.work.token", "synthetic-properties-token");
    var connection = CloudConnection.fromConfiguration("Work", properties::get, ENVIRONMENT::get);
    assertEquals(URI.create("https://properties.example.invalid"), connection.baseUrl());
    assertEquals(
        basic("properties@example.invalid", "synthetic-properties-token"),
        connection.authorization());
    assertEquals("CloudConnection[redacted]", connection.toString());
    assertThrows(
        ProfileException.class,
        () -> CloudConnection.fromConfiguration("other", properties::get, ENVIRONMENT::get));
  }

  @Test
  void absentPropertiesFallBackPerSetting() {
    var properties = Map.of("jira.work.token", "synthetic-properties-token");
    var connection = CloudConnection.fromConfiguration("work", properties::get, ENVIRONMENT::get);
    assertEquals(URI.create(ENVIRONMENT.get("JIRA_WORK_URL")), connection.baseUrl());
    assertEquals(
        basic("environment@example.invalid", "synthetic-properties-token"),
        connection.authorization());
  }

  @Test
  void explicitlyBlankPropertiesDoNotFallBackToEnvironment() {
    for (String key : new String[] {"url", "email", "token"}) {
      var properties = Map.of("jira.work." + key, "");
      assertThrows(
          ProfileException.class,
          () -> CloudConnection.fromConfiguration("work", properties::get, ENVIRONMENT::get));
    }
  }

  @Test
  void invalidConnectionNamesAreRejectedBeforeLookingUpSettings() {
    assertThrows(
        ProfileException.class,
        () ->
            CloudConnection.fromConfiguration(
                "../work", key -> fail("Unexpected lookup"), ENVIRONMENT::get));
  }

  private static String basic(String email, String token) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
  }
}
