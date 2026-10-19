package dev.vspex.jira.mark.profile;

import java.util.Map;

/** Configuration from YAML front matter and guidance from the Markdown body. */
public record Profile(
    String name,
    String project,
    String issueType,
    Map<String, FieldDefinition> fields,
    String guidance) {}
