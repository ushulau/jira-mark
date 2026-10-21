package dev.vspex.jira.mark.remote;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Transport-independent capabilities used by explicit remote workflows. */
public interface JiraGateway {
  Metadata metadata(String project, String issueType);

  ExampleIssue example(String issueKey);

  CreatedIssue create(Map<String, Object> payload);

  default IssueSnapshot readIssue(String issueKey, Set<String> fieldIds) {
    throw new JiraFailure(
        JiraFailure.Code.REMOTE_VALIDATION, "This gateway does not support pulling");
  }

  record IssueSnapshot(
      String key,
      String projectId,
      String projectKey,
      String issueTypeId,
      String issueTypeName,
      Map<String, Object> fields) {
    public IssueSnapshot {
      fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }
  }

  default Metadata editMetadata(String issueKey) {
    throw new JiraFailure(
        JiraFailure.Code.REMOTE_VALIDATION, "This gateway does not support editing");
  }

  default CreatedIssue update(String issueKey, Map<String, Object> payload) {
    throw new JiraFailure(
        JiraFailure.Code.REMOTE_VALIDATION, "This gateway does not support editing");
  }

  /** Identifies the remote deployment without including credentials. */
  default String publicationScope() {
    return getClass().getName();
  }

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
