package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;

import dev.vspex.jira.mark.remote.JiraFailure;
import dev.vspex.jira.mark.remote.JiraGateway;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Jira Cloud v3 and Data Center v2 adapter using paginated create metadata. */
public final class CloudJiraGateway implements JiraGateway {
  private final CloudConnection connection;
  private final CloudTransport http;

  public CloudJiraGateway(CloudConnection connection) {
    this(connection, Duration.ofSeconds(15));
  }

  public CloudJiraGateway(CloudConnection connection, Duration timeout) {
    this.connection = connection;
    http = new CloudTransport(connection, timeout);
  }

  @Override
  public Metadata metadata(String project, String issueType) {
    var projectData = http.get(connection.apiPath() + "/project/" + segment(project));
    String projectId = id(projectData, "id");
    String path = connection.apiPath() + "/issue/createmeta/" + projectId + "/issuetypes";
    boolean dataCenter = connection.deployment() == CloudConnection.Deployment.DATA_CENTER;
    var types = pages(path, dataCenter ? "values" : "issueTypes");
    var exactIds = types.stream().filter(type -> issueType.equals(type.get("id"))).toList();
    var matches =
        exactIds.isEmpty()
            ? types.stream().filter(type -> issueType.equals(type.get("name"))).toList()
            : exactIds;
    if (matches.size() != 1) {
      throw new JiraFailure(
          REMOTE_VALIDATION, "Issue type is unavailable or ambiguous; use its exact id");
    }
    String typeId = id(matches.get(0), "id");
    var fields = new LinkedHashMap<String, Field>();
    for (var field : pages(path + "/" + typeId, dataCenter ? "values" : "fields")) {
      String fieldId = id(field, "fieldId");
      if (!fieldId.matches("[A-Za-z][A-Za-z0-9_]*")) {
        throw invalid();
      }
      if (fields.putIfAbsent(fieldId, parseField(fieldId, field)) != null) {
        throw invalid();
      }
    }
    return new Metadata(projectId, typeId, fields);
  }

  @Override
  public ExampleIssue example(String issueKey) {
    if (!issueKey.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+")) {
      throw new JiraFailure(INVALID_INPUT, "Issue must be a Jira issue key");
    }
    // Only reusable candidates are fetched; summary, description, and user fields are excluded.
    var data =
        http.get(
            connection.apiPath()
                + "/issue/"
                + segment(issueKey)
                + "?fields=project,issuetype,labels,components,versions,fixVersions,priority");
    var fields = object(data.get("fields"));
    String key = id(data, "key");
    if (!key.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+")) {
      throw invalid();
    }
    return new ExampleIssue(
        key,
        id(object(fields.get("project")), "id"),
        id(object(fields.get("issuetype")), "id"),
        fields);
  }

  @Override
  public CreatedIssue create(Map<String, Object> payload) {
    var data = http.post(connection.apiPath() + "/issue", payload);
    Object key = data.get("key");
    if (!(key instanceof String text) || !text.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+")) {
      throw CloudTransport.uncertain();
    }
    return new CreatedIssue(text, URI.create(connection.baseUrl() + "/browse/" + text));
  }

  private List<Map<String, Object>> pages(String path, String key) {
    var result = new ArrayList<Map<String, Object>>();
    int start = 0;
    for (int pageNumber = 0; pageNumber < 1000; pageNumber++) {
      var page = http.get(path + "?startAt=" + start + "&maxResults=100");
      if (!(page.get(key) instanceof List<?> entries)) {
        throw invalid();
      }
      if (!(page.get("startAt") instanceof Long offset) || offset != start) {
        throw invalid();
      }
      for (Object entry : entries) {
        result.add(object(entry));
      }
      if (Boolean.TRUE.equals(page.get("isLast"))) {
        return result;
      }
      if (!(page.get("total") instanceof Long total) || total < start + entries.size()) {
        throw invalid();
      }
      start += entries.size();
      if (start >= total.longValue()) {
        return result;
      }
      if (entries.isEmpty()) {
        throw invalid();
      }
    }
    throw new JiraFailure(INVALID_RESPONSE, "Jira metadata exceeds the pagination limit");
  }

  private Field parseField(String id, Map<String, Object> data) {
    var schema = data.get("schema") instanceof Map<?, ?> map ? map : Map.of();
    String type = String.valueOf(schema.get("type"));
    String custom = String.valueOf(schema.getOrDefault("custom", null));
    boolean supportedCustom =
        !schema.containsKey("custom")
            || Set.of("textfield", "textarea", "float", "select", "multiselect").stream()
                .anyMatch(
                    name ->
                        custom.equals("com.atlassian.jira.plugin.system.customfieldtypes:" + name));
    String local = null;
    String encoding = "unsupported";
    if (supportedCustom) {
      switch (type) {
        case "string" -> {
          local = "string";
          encoding =
              connection.deployment() == CloudConnection.Deployment.CLOUD
                      && (id.equals("description")
                          || id.equals("environment")
                          || custom.endsWith(":textarea"))
                  ? "adf"
                  : "literal";
        }
        case "number", "integer", "boolean" -> {
          local = type;
          encoding = "literal";
        }
        case "priority", "option", "component", "version" -> {
          local = "object";
          encoding = "reference";
        }
        case "array" -> {
          if ("string".equals(schema.get("items"))) {
            local = "string-list";
            encoding = "literal";
          } else if (Set.of("option", "component", "version")
              .contains(String.valueOf(schema.get("items")))) {
            local = "array";
            encoding = "references";
          }
        }
        default -> {}
      }
    }
    boolean settable =
        data.get("operations") instanceof List<?> operations && operations.contains("set");
    var choices =
        data.get("allowedValues") instanceof List<?> values
            ? new ArrayList<Object>(values)
            : List.<Object>of();
    if (!(data.get("required") instanceof Boolean)
        || choices.stream().anyMatch(java.util.Objects::isNull)
        || data.containsKey("allowedValues") && !(data.get("allowedValues") instanceof List<?>)
        || data.containsKey("hasDefaultValue")
            && !(data.get("hasDefaultValue") instanceof Boolean)) {
      throw invalid();
    }
    return new Field(
        id,
        local,
        Boolean.TRUE.equals(data.get("required")),
        Boolean.TRUE.equals(data.get("hasDefaultValue")),
        settable,
        encoding,
        choices);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?>)) {
      throw invalid();
    }
    return (Map<String, Object>) value;
  }

  private static String id(Map<String, Object> value, String key) {
    if (!(value.get(key) instanceof String text) || !text.matches("[A-Za-z0-9_-]+")) {
      throw invalid();
    }
    return text;
  }

  private static String segment(String value) {
    if (value == null || value.isBlank()) {
      throw new JiraFailure(INVALID_INPUT, "Project and issue type must be explicit");
    }
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static JiraFailure invalid() {
    return new JiraFailure(INVALID_RESPONSE, "Jira returned incomplete or inconsistent metadata");
  }
}
