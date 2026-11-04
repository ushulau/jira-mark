package dev.vspex.jira.mark.remote;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared date and datetime validation shared by encoders, decoders, and pull/create/update. */
public final class Datetimes {
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final DateTimeFormatter JIRA =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

  private Datetimes() {}

  /** Validates a {@code yyyy-MM-dd} date without interpreting it as a timestamp. */
  public static String encodeDate(String value, String alias) {
    if (value == null || !DATE.matcher(value).matches()) {
      throw new JiraFailure(
          JiraFailure.Code.INVALID_INPUT,
          "Field '" + alias + "' must use yyyy-MM-dd, for example 2026-11-04");
    }
    try {
      java.time.LocalDate.parse(value);
    } catch (DateTimeParseException failure) {
      throw new JiraFailure(
          JiraFailure.Code.INVALID_INPUT, "Field '" + alias + "' is not a calendar date");
    }
    return value;
  }

  /**
   * Accepts ISO-8601 with an offset or {@code Z} and formats for Jira as {@code
   * yyyy-MM-dd'T'HH:mm:ss.SSSZ}. Values without an offset are ambiguous and rejected.
   */
  public static String encodeDatetime(String value, String alias) {
    if (value == null) {
      throw new JiraFailure(
          JiraFailure.Code.INVALID_INPUT, "Field '" + alias + "' must have a datetime value");
    }
    OffsetDateTime parsed;
    try {
      parsed = OffsetDateTime.parse(value);
    } catch (DateTimeParseException failure) {
      throw new JiraFailure(
          JiraFailure.Code.INVALID_INPUT,
          "Field '"
              + alias
              + "' must be ISO-8601 with an offset, for example 2026-11-04T06:00:00-06:00");
    }
    return parsed.format(JIRA);
  }

  /** Normalizes a Jira datetime to ISO-8601 with a colon offset for ticket files. */
  public static String decodeDatetime(Object value, String alias) {
    if (!(value instanceof String text)) {
      throw new JiraFailure(
          JiraFailure.Code.REMOTE_VALIDATION, "Field '" + alias + "' is not a datetime string");
    }
    try {
      return OffsetDateTime.parse(text).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    } catch (DateTimeParseException first) {
      try {
        return OffsetDateTime.parse(text, JIRA).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      } catch (DateTimeParseException second) {
        // Fall back to the raw value when Jira returns an unexpected but string shape.
        return text;
      }
    }
  }

  /** Converts a Jira user reference to the local shorthand (name on DC, accountId on Cloud). */
  public static String decodeUser(Object value, boolean cloud, String alias) {
    if (value instanceof String text) {
      return text;
    }
    if (value instanceof Map<?, ?> map) {
      if (cloud && map.get("accountId") instanceof String account && !account.isBlank()) {
        return account;
      }
      if (!cloud && map.get("name") instanceof String name && !name.isBlank()) {
        return name;
      }
      // Account ids and names are both accepted on read; prefer whichever is present.
      if (map.get("accountId") instanceof String account && !account.isBlank()) {
        return account;
      }
      if (map.get("name") instanceof String name && !name.isBlank()) {
        return name;
      }
      if (map.get("key") instanceof String key && !key.isBlank()) {
        return key;
      }
    }
    if (value == null) {
      return null;
    }
    throw new JiraFailure(
        JiraFailure.Code.REMOTE_VALIDATION, "Field '" + alias + "' is not a user reference");
  }

  public static java.util.List<String> decodeUsers(Object value, boolean cloud, String alias) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof java.util.List<?> list)) {
      throw new JiraFailure(
          JiraFailure.Code.REMOTE_VALIDATION, "Field '" + alias + "' is not a user list");
    }
    var result = new java.util.ArrayList<String>();
    for (Object item : list) {
      result.add(decodeUser(item, cloud, alias));
    }
    return new java.util.ArrayList<>(result);
  }

  /** Encodes a local user shorthand; Cloud requires an accountId, DC accepts a name. */
  public static Map<String, String> encodeUser(Object value, boolean cloud, String alias) {
    String text = userText(value, alias);
    if (cloud) {
      if (!isAccountId(text)) {
        throw new JiraFailure(
            JiraFailure.Code.INVALID_INPUT,
            "Field '" + alias + "' needs a Cloud accountId; user lookup is not supported");
      }
      return Map.of("accountId", text);
    }
    return Map.of("name", text);
  }

  public static java.util.List<Map<String, String>> encodeUsers(
      Object value, boolean cloud, String alias) {
    if (!(value instanceof java.util.List<?> list)) {
      throw new JiraFailure(
          JiraFailure.Code.INVALID_INPUT, "Field '" + alias + "' must be a list of users");
    }
    var result = new java.util.ArrayList<Map<String, String>>();
    for (Object item : list) {
      result.add(encodeUser(item, cloud, alias));
    }
    return java.util.List.copyOf(result);
  }

  private static String userText(Object value, String alias) {
    if (value instanceof String text && !text.isBlank()) {
      return text.trim();
    }
    if (value instanceof Map<?, ?> map) {
      for (String key : List.of("accountId", "name", "key", "id")) {
        if (map.get(key) instanceof String text && !text.isBlank()) {
          return text.trim();
        }
      }
    }
    throw new JiraFailure(
        JiraFailure.Code.INVALID_INPUT, "Field '" + alias + "' must be a user id or name");
  }

  private static boolean isAccountId(String text) {
    // Cloud accountIds are long opaque strings; names and keys are shorter or contain spaces.
    return text.length() >= 20 && !text.contains(" ") || text.contains(":");
  }
}
