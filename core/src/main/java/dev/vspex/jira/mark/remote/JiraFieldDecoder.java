package dev.vspex.jira.mark.remote;

import dev.vspex.jira.mark.profile.FieldDefinition;

/**
 * Converts fetched Jira fields to local profile values without discarding unsupported structure.
 */
@FunctionalInterface
public interface JiraFieldDecoder {
  Object decode(FieldDefinition field, Object value, String alias);
}
