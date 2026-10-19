package dev.vspex.jira.mark.remote;

/** Converts a validated local field value to the target API representation. */
@FunctionalInterface
public interface JiraFieldEncoder {
  Object encode(JiraGateway.Field field, Object value, String alias);
}
