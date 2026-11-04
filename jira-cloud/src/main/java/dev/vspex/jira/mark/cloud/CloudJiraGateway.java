package dev.vspex.jira.mark.cloud;

import static dev.vspex.jira.mark.remote.JiraFailure.Code.*;

import dev.vspex.jira.mark.remote.FieldKind;
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

  @Override
  public Metadata editMetadata(String issueKey) {
    var issue = example(issueKey);
    var data = http.get(connection.apiPath() + "/issue/" + segment(issueKey) + "/editmeta");
    var fields = new LinkedHashMap<String, Field>();
    object(data.get("fields"))
        .forEach(
            (fieldId, definition) -> {
              if (!fieldId.matches("[A-Za-z][A-Za-z0-9_]*")) throw invalid();
              fields.put(fieldId, parseField(fieldId, object(definition)));
            });
    return new Metadata(issue.projectId(), issue.issueTypeId(), fields);
  }

  @Override
  public CreatedIssue update(String issueKey, Map<String, Object> payload) {
    if (issueKey == null || !issueKey.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+")) {
      throw new JiraFailure(INVALID_INPUT, "Issue must be a Jira issue key");
    }
    http.put(connection.apiPath() + "/issue/" + segment(issueKey), payload);
    return new CreatedIssue(issueKey, URI.create(connection.baseUrl() + "/browse/" + issueKey));
  }

  @Override
  public String publicationScope() {
    return connection.deployment() + ":" + connection.baseUrl();
  }

  @Override
  public IssueSnapshot readIssue(String issueKey, Set<String> fieldIds) {
    if (issueKey == null
        || !issueKey.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+")
        || fieldIds.stream().anyMatch(field -> !field.matches("[A-Za-z][A-Za-z0-9_]*"))) {
      throw new JiraFailure(
          INVALID_INPUT, "Pull requires an exact Jira issue key and valid field IDs");
    }
    var selected = new java.util.TreeSet<>(fieldIds);
    selected.addAll(Set.of("project", "issuetype"));
    var data =
        http.get(
            connection.apiPath()
                + "/issue/"
                + segment(issueKey)
                + "?fields="
                + String.join(",", selected));
    var fields = object(data.get("fields"));
    var project = object(fields.get("project"));
    var type = object(fields.get("issuetype"));
    String key = id(data, "key");
    if (!key.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+")) throw invalid();
    Object typeName = type.get("name");
    if (!(typeName instanceof String name) || name.isBlank()) throw invalid();
    var requested = new LinkedHashMap<String, Object>();
    for (String field : fieldIds) {
      if (!fields.containsKey(field)) {
        throw new JiraFailure(
            REMOTE_VALIDATION,
            "A requested Jira field is unavailable; verify profile mappings and read permissions");
      }
      requested.put(field, fields.get(field));
    }
    return new IssueSnapshot(
        key, id(project, "id"), id(project, "key"), id(type, "id"), name, requested);
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
    String type = schema.get("type") instanceof String text ? text : null;
    String items = schema.get("items") instanceof String text ? text : null;
    String custom = schema.get("custom") instanceof String text ? text : null;
    Object name = data.get("name");
    String fieldName = name instanceof String text && !text.isBlank() ? text : null;
    FieldKind kind = FieldKind.classify(custom, type, items);
    boolean cloud = connection.deployment() == CloudConnection.Deployment.CLOUD;
    String local = kind.profileType();
    String encoding =
        switch (kind) {
          case TEXT ->
              cloud
                      && (id.equals("description")
                          || id.equals("environment")
                          || (custom != null && custom.endsWith(":textarea")))
                  ? "adf"
                  : "literal";
          case INTEGER, NUMBER, BOOLEAN, LABELS -> "literal";
          case SELECT, VERSION -> "reference";
          case MULTISELECT, MULTI_VERSION -> "references";
          case CASCADING -> "cascading";
          case DATE -> "date";
          case DATETIME -> "datetime";
          case USER -> cloud ? "user-cloud" : "user-dc";
          case MULTI_USER -> cloud ? "users-cloud" : "users-dc";
          case EPIC_LINK -> "epic-link";
          case UNSUPPORTED -> "unsupported";
        };
    if (!kind.supported()) {
      local = null;
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
        fieldName,
        kind.kind(),
        local,
        Boolean.TRUE.equals(data.get("required")),
        Boolean.TRUE.equals(data.get("hasDefaultValue")),
        settable,
        encoding,
        choices,
        custom);
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
