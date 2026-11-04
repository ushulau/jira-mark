package dev.vspex.jira.mark.profile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** A repository alias mapped to a Jira field identifier and a local value contract. */
public record FieldDefinition(
    String id,
    String type,
    boolean required,
    Object defaultValue,
    List<Object> allowedValues,
    String name,
    Integer allowedValuesCount) {
  public static final List<String> TYPES =
      List.of("string", "integer", "number", "boolean", "string-list", "object", "array");

  public FieldDefinition(
      String id, String type, boolean required, Object defaultValue, List<Object> allowedValues) {
    this(id, type, required, defaultValue, allowedValues, null, null);
  }

  public FieldDefinition {
    allowedValues = List.copyOf(allowedValues == null ? List.of() : allowedValues);
  }

  public boolean accepts(Object value) {
    if (value == null) {
      return true;
    }
    return switch (type) {
      case "string" -> value instanceof String;
      case "integer" -> value instanceof Number number && integral(number);
      case "number" -> value instanceof Number number && Double.isFinite(number.doubleValue());
      case "boolean" -> value instanceof Boolean;
      case "string-list" ->
          value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
        // Object fields accept a bare label/user shorthand string as well as a reference map.
      case "object" -> value instanceof Map<?, ?> || value instanceof String;
      case "array" -> value instanceof List<?>;
      default -> false;
    };
  }

  public Object parseArgument(String value, String alias) {
    if (type.equals("string")) {
      return value;
    }
    try {
      return switch (type) {
        case "integer" -> Long.parseLong(value);
        case "number" -> new BigDecimal(value);
        case "boolean" -> {
          if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException();
          }
          yield Boolean.valueOf(value);
        }
        default -> dev.vspex.jira.mark.json.Json.parse(value);
      };
    } catch (IllegalArgumentException exception) {
      throw new ProfileException("Invalid value for field '" + alias + "'; expected " + type);
    }
  }

  private static boolean integral(Number value) {
    try {
      new BigDecimal(value.toString()).toBigIntegerExact();
      return true;
    } catch (NumberFormatException | ArithmeticException exception) {
      return false;
    }
  }

  public void validate(Object value, String alias) {
    if (!accepts(value)) {
      throw new ProfileException("Field '" + alias + "' must have type " + type);
    }
    if (value == null || allowedValues.isEmpty()) {
      return;
    }
    if (allowedValues.get(0) instanceof Map<?, ?>) {
      // Object-shaped allowedValues (id/value pairs): membership is resolved against live
      // metadata or the profile list by the option resolver, which reports valid labels.
      return;
    }
    if (!allowedValues.contains(value)) {
      throw new ProfileException("Field '" + alias + "' is outside its allowedValues");
    }
  }

  public boolean arrayKind() {
    return type.equals("array") || type.equals("string-list");
  }

  /** Option entries from a profile: scalar legacy values or id/value objects. */
  public List<Map<String, String>> optionEntries() {
    var entries = new java.util.ArrayList<Map<String, String>>();
    for (Object item : allowedValues) {
      if (item instanceof Map<?, ?> map) {
        Object id = map.get("id");
        Object label = map.get("value");
        if (label == null) {
          label = map.get("name");
        }
        if (id instanceof String idText && label instanceof String labelText) {
          entries.add(Map.of("id", idText, "value", labelText));
        }
      }
    }
    return List.copyOf(entries);
  }
}
