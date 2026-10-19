package dev.vspex.jira.mark.profile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** A repository alias mapped to a Jira field identifier and a local value contract. */
public record FieldDefinition(
    String id, String type, boolean required, Object defaultValue, List<Object> allowedValues) {
  public static final List<String> TYPES =
      List.of("string", "integer", "number", "boolean", "string-list", "object", "array");

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
      case "object" -> value instanceof Map<?, ?>;
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
    if (value != null && !allowedValues.isEmpty() && !allowedValues.contains(value)) {
      throw new ProfileException("Field '" + alias + "' is outside its allowedValues");
    }
  }
}
