package dev.vspex.jira.mark.remote;

import java.util.List;

/**
 * Generated field reference table owned by the plugin. Everything between the markers is rewritten
 * on every import or sync; text outside the markers is never touched.
 */
public final class ProfileDocs {
  public static final String BEGIN = "<!-- jira-mark:fields:begin -->";
  public static final String END = "<!-- jira-mark:fields:end -->";

  private ProfileDocs() {}

  public record Row(
      String fieldName,
      String fieldId,
      String alias,
      String kind,
      boolean required,
      List<String> allowedLabels,
      List<String> allowedIds,
      Integer allowedCount) {}

  /** Renders the {@code ## Fields} table for the given rows, in order. */
  public static String renderTable(List<Row> rows) {
    var out = new StringBuilder();
    out.append("## Fields\n");
    out.append("| Field | Jira id | Alias | Kind | Required | Allowed values |\n");
    out.append("|---|---|---|---|---|---|\n");
    for (Row row : rows) {
      out.append("| ")
          .append(escape(row.fieldName()))
          .append(" | `")
          .append(row.fieldId())
          .append("` | ");
      if (row.alias() == null || row.alias().isBlank()) {
        out.append(" ");
      } else {
        out.append("`").append(row.alias()).append("`");
      }
      out.append(" | ").append(row.kind()).append(" | ").append(row.required() ? "yes" : "no");
      out.append(" | ").append(allowedCell(row)).append(" |\n");
    }
    return out.toString();
  }

  private static String allowedCell(Row row) {
    if (row.allowedCount() != null) {
      return row.allowedCount() + " values";
    }
    if (row.allowedLabels() == null || row.allowedLabels().isEmpty()) {
      return "";
    }
    var parts = new StringBuilder();
    for (int i = 0; i < row.allowedLabels().size(); i++) {
      if (i > 0) {
        parts.append("; ");
      }
      parts.append(row.allowedLabels().get(i));
      if (row.allowedIds() != null && i < row.allowedIds().size()) {
        parts.append(" (`").append(row.allowedIds().get(i)).append("`)");
      }
    }
    return parts.toString();
  }

  private static String escape(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("|", "\\|").replace("\n", " ");
  }

  /**
   * Replaces the marker section in {@code body} with {@code table}, or appends it at the end when
   * the markers are missing (old profiles).
   */
  public static String upsertSection(String body, String table) {
    String section = BEGIN + "\n" + table.strip() + "\n" + END;
    String normalized = body == null ? "" : body.replace("\r\n", "\n");
    int begin = normalized.indexOf(BEGIN);
    int end = normalized.indexOf(END);
    if (begin >= 0 && end > begin) {
      String before = normalized.substring(0, begin);
      String after = normalized.substring(end + END.length());
      if (!before.endsWith("\n")) {
        before = before + "\n";
      }
      if (!after.startsWith("\n")) {
        after = "\n" + after;
      }
      return (before + section + after).strip() + "\n";
    }
    String base = normalized.strip();
    if (base.isEmpty()) {
      return section + "\n";
    }
    return base + "\n\n" + section + "\n";
  }

  /** Extracts the current marker section, or an empty string when absent. */
  public static String currentSection(String body) {
    if (body == null) {
      return "";
    }
    int begin = body.indexOf(BEGIN);
    int end = body.indexOf(END);
    if (begin >= 0 && end > begin) {
      return body.substring(begin, end + END.length());
    }
    return "";
  }
}
