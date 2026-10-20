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

  @Test
  void dataCenterUsesBearerWithoutEmailAndDoesNotGuessFromMissingEmail() {
    var properties =
        Map.of(
            "jira.work.url", "https://jira.example.invalid/jira/",
            "jira.work.type", "data-center",
            "jira.work.token", "synthetic-pat");
    var connection = CloudConnection.fromConfiguration("work", properties::get, key -> null);
    assertEquals("Bearer synthetic-pat", connection.authorization());
    assertEquals(CloudConnection.Deployment.DATA_CENTER, connection.deployment());
    assertEquals("/rest/api/2", connection.apiPath());
    assertEquals(URI.create("https://jira.example.invalid/jira"), connection.baseUrl());
    assertFalse(connection.toString().contains("synthetic-pat"));
    assertThrows(
        ProfileException.class,
        () ->
            CloudConnection.fromConfiguration(
                "work",
                key -> key.equals("jira.work.type") ? null : properties.get(key),
                key -> null));
  }

  @Test
  void deploymentTypeUsesPropertyPrecedenceAndRejectsInvalidExplicitValues() {
    var environment = new java.util.HashMap<>(ENVIRONMENT);
    environment.put("JIRA_WORK_TYPE", "data-center");
    assertEquals(
        "Bearer synthetic-environment-token",
        CloudConnection.fromEnvironment("work", environment::get).authorization());
    assertEquals(
        "/rest/api/3",
        CloudConnection.fromConfiguration(
                "work", Map.of("jira.work.type", "cloud")::get, environment::get)
            .apiPath());
    for (String invalid : new String[] {"", "bearer", "unknown"}) {
      assertThrows(
          ProfileException.class,
          () ->
              CloudConnection.fromConfiguration(
                  "work", Map.of("jira.work.type", invalid)::get, environment::get));
    }
    for (String token : new String[] {"", "bad\r\nheader", "has space"}) {
      assertThrows(
          ProfileException.class,
          () ->
              new CloudConnection(
                  URI.create("https://jira.example.invalid"),
                  null,
                  token,
                  CloudConnection.Deployment.DATA_CENTER));
    }
  }
}
