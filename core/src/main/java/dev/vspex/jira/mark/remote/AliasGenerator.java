package dev.vspex.jira.mark.remote;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic readable aliases for Jira field names.
 *
 * <p>Same Jira name in, same alias out, on every machine and run. See the implementation plan for
 * the full rule list and examples.
 */
public final class AliasGenerator {
  private static final Set<String> STOP_WORDS =
      Set.of(
          "a", "an", "the", "of", "for", "to", "in", "on", "at", "by", "is", "are", "be", "been",
          "has", "have", "this", "that", "was", "were", "will", "with");
  private static final Set<String> STANDARD_IDS =
      Set.of(
          "summary",
          "description",
          "labels",
          "priority",
          "components",
          "versions",
          "fixversions",
          "assignee",
          "reporter",
          "duedate",
          "environment");
  private static final Pattern COMMENTS =
      Pattern.compile("^comments?\\s*[-:]?\\s*(.+)$", Pattern.CASE_INSENSITIVE);

  private AliasGenerator() {}

  /** Base alias for one Jira field name without collision handling. */
  public static String baseAlias(String name, String style, Set<String> dropWords) {
    if (name == null) {
      return "field";
    }
    String normalized =
        Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "").trim();
    normalized = normalized.replaceAll("\\(([A-Za-z]+)\\)\\(s\\)", "$1s");
    normalized = normalized.replaceAll("(?i)\\b([A-Za-z]+)\\(s\\)", "$1s");
    normalized = normalized.replace("&", " and ");
    normalized = normalized.replace("+", " plus ");
    normalized = normalized.replace("#", " number ");
    String remainder = normalized;
    boolean comments = false;
    var comment = COMMENTS.matcher(normalized);
    if (comment.matches()) {
      remainder = comment.group(1).trim();
      comments = true;
      if (remainder.isEmpty()) {
        remainder = normalized;
        comments = false;
      }
    }
    List<String> words = splitWords(remainder);
    if ("short".equals(style)) {
      Set<String> drops =
          dropWords == null
              ? STOP_WORDS
              : new java.util.HashSet<>(STOP_WORDS) {
                {
                  addAll(dropWords);
                }
              };
      var kept = new ArrayList<String>();
      for (String word : words) {
        if (!drops.contains(word)) {
          kept.add(word);
        }
      }
      if (!kept.isEmpty()) {
        words = kept;
      } else {
        words = splitWords(remainder);
      }
    }
    if (words.isEmpty()) {
      words = List.of("field");
    }
    StringBuilder alias = new StringBuilder();
    for (int i = 0; i < words.size(); i++) {
      String word = words.get(i);
      if (i == 0) {
        alias.append(word);
      } else {
        alias.append(Character.toUpperCase(word.charAt(0)));
        alias.append(word.substring(1));
      }
    }
    if (comments) {
      String base = alias.toString();
      alias.setLength(0);
      alias.append(base);
      alias.append("Comments");
    }
    String result = alias.toString();
    if (result.isEmpty()) {
      result = "field";
    }
    if (Character.isDigit(result.charAt(0))) {
      result = "f" + result;
    }
    return result;
  }

  private static List<String> splitWords(String text) {
    var words = new ArrayList<String>();
    for (String part : text.split("[^A-Za-z0-9]+")) {
      if (part.isEmpty()) {
        continue;
      }
      // Split camelCase inside a word: "rAndD" -> [r, And, D], "camelCase" -> [camel, Case].
      var current = new StringBuilder();
      for (int i = 0; i < part.length(); i++) {
        char c = part.charAt(i);
        char next = i + 1 < part.length() ? part.charAt(i + 1) : 0;
        char prev = i > 0 ? part.charAt(i - 1) : 0;
        boolean boundary =
            i > 0 && Character.isLowerCase(prev) && Character.isUpperCase(c)
                || i > 0
                    && Character.isUpperCase(prev)
                    && Character.isUpperCase(c)
                    && Character.isLowerCase(next)
                || i > 0
                    && Character.isLetter(prev) != Character.isLetter(c)
                    && !(Character.isDigit(prev) && Character.isDigit(c));
        // Keep digit runs together with the preceding word start (2nd -> 2nd, not 2 + nd split
        // on letter/digit change after a leading digit run).
        if (boundary && !(Character.isDigit(prev) && i == 1)) {
          if (current.length() > 0) {
            words.add(current.toString().toLowerCase(Locale.ROOT));
            current.setLength(0);
          }
        }
        current.append(c);
      }
      if (current.length() > 0) {
        words.add(current.toString().toLowerCase(Locale.ROOT));
      }
    }
    return words;
  }

  /**
   * Generates aliases for every field id, applying collision suffixes to all members of a colliding
   * group so field order never decides the winner.
   *
   * @param names field id to Jira name, in any order
   * @param existing field id to previously generated alias for stability, may be empty
   * @param style {@code full} or {@code short}
   * @param dropWords extra stop words for {@code short} style
   */
  public static Map<String, String> generate(
      Map<String, String> names,
      Map<String, String> existing,
      String style,
      Set<String> dropWords) {
    Map<String, String> base = new LinkedHashMap<>();
    for (var entry : names.entrySet()) {
      String kept = existing.get(entry.getKey());
      if (kept != null && !kept.isBlank()) {
        base.put(entry.getKey(), kept);
      } else {
        base.put(entry.getKey(), baseAlias(entry.getValue(), style, dropWords));
      }
    }
    Map<String, List<String>> groups = new LinkedHashMap<>();
    for (var entry : base.entrySet()) {
      String key = entry.getValue().toLowerCase(Locale.ROOT);
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry.getKey());
    }
    // Standard id collisions apply even to singletons.
    Map<String, String> result = new LinkedHashMap<>(base);
    for (var group : groups.values()) {
      boolean collides =
          group.size() > 1
              || (group.size() == 1
                  && STANDARD_IDS.contains(base.get(group.get(0)).toLowerCase(Locale.ROOT)));
      if (collides) {
        for (String fieldId : group) {
          String numeric = fieldId.replaceAll("[^0-9]", "");
          result.put(fieldId, base.get(fieldId) + numeric);
        }
      }
    }
    return result;
  }

  public static List<String> longAliasNotes(Map<String, String> aliases, int maxLength) {
    var notes = new ArrayList<String>();
    for (var entry : aliases.entrySet()) {
      if (entry.getValue().length() > maxLength) {
        notes.add("Long alias: " + entry.getValue() + " (" + entry.getKey() + ")");
      }
    }
    return notes;
  }
}
