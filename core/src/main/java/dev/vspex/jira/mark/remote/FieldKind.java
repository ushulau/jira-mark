package dev.vspex.jira.mark.remote;

import java.util.Set;

/**
 * Single registry mapping Jira schema metadata to a local profile contract.
 *
 * <p>Classification prefers {@code schema.custom} first, then {@code schema.type} and {@code
 * schema.items} for system fields. Unknown schemas stay {@link #UNSUPPORTED}; kinds are never
 * guessed.
 */
public enum FieldKind {
  TEXT("text", "string"),
  INTEGER("integer", "integer"),
  NUMBER("number", "number"),
  BOOLEAN("boolean", "boolean"),
  SELECT("select", "object"),
  MULTISELECT("multiselect", "array"),
  CASCADING("cascading", "object"),
  DATE("date", "string"),
  DATETIME("datetime", "string"),
  USER("user", "object"),
  MULTI_USER("multi-user", "array"),
  LABELS("labels", "string-list"),
  VERSION("version", "object"),
  MULTI_VERSION("multi-version", "array"),
  EPIC_LINK("epic-link", "string"),
  UNSUPPORTED("unsupported", null);

  private final String kind;
  private final String profileType;

  FieldKind(String kind, String profileType) {
    this.kind = kind;
    this.profileType = profileType;
  }

  public String kind() {
    return kind;
  }

  /** Local profile {@code type}, or {@code null} when the kind is not written to profiles. */
  public String profileType() {
    return profileType;
  }

  public boolean supported() {
    return this != UNSUPPORTED;
  }

  /**
   * Classifies a field from its schema metadata.
   *
   * @param custom Jira {@code schema.custom} key, may be null
   * @param schemaType Jira {@code schema.type}, may be null
   * @param schemaItems Jira {@code schema.items}, may be null
   */
  public static FieldKind classify(String custom, String schemaType, String schemaItems) {
    String type = schemaType == null ? "" : schemaType;
    String items = schemaItems == null ? "" : schemaItems;
    String customKey = custom == null ? "" : custom;
    if (customKey.equals("com.pyxis.greenhopper.jira:gh-epic-link")) {
      return EPIC_LINK;
    }
    if (customKey.endsWith(":cascadingselect")) {
      return CASCADING;
    }
    if (customKey.endsWith(":multicheckboxes") || customKey.endsWith(":multiselect")) {
      return MULTISELECT;
    }
    if (customKey.endsWith(":multiuserpicker")) {
      return MULTI_USER;
    }
    if (customKey.endsWith(":userpicker")) {
      return USER;
    }
    if (customKey.endsWith(":datepicker")) {
      return DATE;
    }
    if (customKey.endsWith(":datetime")) {
      return DATETIME;
    }
    if (customKey.endsWith(":select") || customKey.endsWith(":radiobuttons")) {
      return SELECT;
    }
    if (customKey.endsWith(":labels")) {
      return LABELS;
    }
    if (customKey.endsWith(":version")) {
      return VERSION;
    }
    if (customKey.endsWith(":multiversion")) {
      return MULTI_VERSION;
    }
    if (customKey.endsWith(":float")) {
      return NUMBER;
    }
    if (customKey.endsWith(":textfield")
        || customKey.endsWith(":textarea")
        || customKey.endsWith(":url")) {
      return TEXT;
    }
    if (!customKey.isEmpty()) {
      // Unknown custom types are never guessed.
      return UNSUPPORTED;
    }
    // System fields without a custom key resolve by schema shape.
    if (type.equals("array") && items.equals("option")) {
      return MULTISELECT;
    }
    if (type.equals("array") && items.equals("user")) {
      return MULTI_USER;
    }
    if (type.equals("array") && items.equals("version")) {
      return MULTI_VERSION;
    }
    if (type.equals("array") && items.equals("component")) {
      return MULTISELECT;
    }
    if (type.equals("array") && items.equals("string")) {
      return LABELS;
    }
    if (type.equals("user")) {
      return USER;
    }
    if (type.equals("date")) {
      return DATE;
    }
    if (type.equals("datetime")) {
      return DATETIME;
    }
    if (type.equals("option")) {
      return SELECT;
    }
    if (type.equals("number")) {
      return NUMBER;
    }
    if (type.equals("integer")) {
      return INTEGER;
    }
    if (type.equals("boolean")) {
      return BOOLEAN;
    }
    if (type.equals("string")) {
      return TEXT;
    }
    if (Set.of("priority", "component", "version").contains(type)) {
      return SELECT;
    }
    return UNSUPPORTED;
  }
}
