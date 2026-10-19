package dev.vspex.jira.mark.remote;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Transport-independent capabilities used by explicit remote workflows. */
public interface JiraGateway {
  Metadata metadata(String project, String issueType);

  ExampleIssue example(String issueKey);

  CreatedIssue create(Map<String, Object> payload);

  record Field(
      String id,
      String localType,
      boolean required,
      boolean hasDefault,
      boolean settable,
      String encoding,
      List<Object> choices) {
    public Field {
      choices = List.copyOf(choices);
    }

    public boolean supported() {
      return localType != null;
    }
  }

  record Metadata(String projectId, String issueTypeId, Map<String, Field> fields) {
    public Metadata {
      fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }
  }

  /**
   * Only values requested for reuse belong in the import result, never an entire issue response.
   */
  record ExampleIssue(
      String key, String projectId, String issueTypeId, Map<String, Object> fields) {}

  record CreatedIssue(String key, URI url) {}
}
