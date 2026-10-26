package com.devassisthub.bug.llm;

import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.Severity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns the model's raw answer into a {@link Suggestion}, or rejects it with a reason short enough to send back to the
 * model in a repair prompt. Tolerates code fences, a leading {@code <think>} block, surrounding prose, letter case and
 * a few common synonyms ("major", "blocker", "UI/UX"); everything else must match the allowed values exactly.
 */
@Component
public class TriageParser {

    public static final int MIN_TITLE = 5;
    public static final int MAX_TITLE = 120;
    public static final int MAX_RATIONALE = 500;

    /** A validated triage. */
    public record Suggestion(Severity severity, BugCategory category, String title, String rationale) {
    }

    /** The answer cannot be used; the message says why. */
    public static class InvalidOutputException extends Exception {
        public InvalidOutputException(String reason) {
            super(reason);
        }
    }

    private static final Pattern THINK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern TITLE_PREFIX = Pattern.compile("(?i)^(\\[?(bug|issue|defect)]?\\s*[:#-]\\s*|\\[(bug|issue)]\\s*)");

    private static final Map<String, Severity> SEVERITY_ALIASES = Map.of(
            "BLOCKER", Severity.CRITICAL, "URGENT", Severity.CRITICAL, "SEVERE", Severity.HIGH,
            "MAJOR", Severity.HIGH, "MODERATE", Severity.MEDIUM, "NORMAL", Severity.MEDIUM,
            "MINOR", Severity.LOW, "TRIVIAL", Severity.LOW);

    private static final Map<String, BugCategory> CATEGORY_ALIASES = Map.ofEntries(
            Map.entry("UX", BugCategory.UI), Map.entry("UI_UX", BugCategory.UI),
            Map.entry("USER_INTERFACE", BugCategory.UI), Map.entry("FUNCTIONALITY", BugCategory.FUNCTIONAL),
            Map.entry("LOGIC", BugCategory.FUNCTIONAL), Map.entry("PERF", BugCategory.PERFORMANCE),
            Map.entry("STABILITY", BugCategory.CRASH), Map.entry("DATA_LOSS", BugCategory.DATA),
            Map.entry("DATA_INTEGRITY", BugCategory.DATA), Map.entry("API", BugCategory.INTEGRATION),
            Map.entry("NETWORK", BugCategory.INTEGRATION));

    private final ObjectMapper mapper = new ObjectMapper();

    public Suggestion parse(String raw) throws InvalidOutputException {
        if (raw == null || raw.isBlank()) {
            throw new InvalidOutputException("the answer was empty");
        }
        String text = THINK.matcher(raw).replaceAll("");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new InvalidOutputException("the answer contained no JSON object");
        }
        JsonNode node;
        try {
            node = mapper.readTree(text.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            throw new InvalidOutputException("the JSON object was malformed");
        }
        if (node == null || !node.isObject()) {
            throw new InvalidOutputException("the answer was not a JSON object");
        }
        Severity severity = severity(textOf(node, "severity"));
        BugCategory category = category(textOf(node, "category"));
        String title = title(textOf(node, node.hasNonNull("suggested_title") && !node.hasNonNull("title")
                ? "suggested_title" : "title"));
        String rationale = clean(node.path("rationale").asText(""));
        if (rationale.length() > MAX_RATIONALE) {
            rationale = cutAtWord(rationale, MAX_RATIONALE);
        }
        return new Suggestion(severity, category, title, rationale.isEmpty() ? null : rationale);
    }

    private static String textOf(JsonNode node, String field) throws InvalidOutputException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode() || value.asText().isBlank()) {
            throw new InvalidOutputException("\"" + field + "\" was missing");
        }
        return value.asText();
    }

    static Severity severity(String value) throws InvalidOutputException {
        String key = enumKey(value);
        try {
            return Severity.valueOf(key);
        } catch (IllegalArgumentException e) {
            Severity alias = SEVERITY_ALIASES.get(key);
            if (alias != null) {
                return alias;
            }
            throw new InvalidOutputException("severity \"" + value.strip() + "\" is not one of " + allowed(Severity.values()));
        }
    }

    static BugCategory category(String value) throws InvalidOutputException {
        String key = enumKey(value);
        try {
            return BugCategory.valueOf(key);
        } catch (IllegalArgumentException e) {
            BugCategory alias = CATEGORY_ALIASES.get(key);
            if (alias != null) {
                return alias;
            }
            throw new InvalidOutputException("category \"" + value.strip() + "\" is not one of "
                    + allowed(BugCategory.values()));
        }
    }

    static String title(String value) throws InvalidOutputException {
        String title = clean(value);
        title = TITLE_PREFIX.matcher(title).replaceFirst("");
        title = title.replaceAll("^[\"'`“”‘’]+|[\"'`“”‘’]+$", "").strip();
        title = title.replaceAll("[.!]+$", "").strip();
        if (title.length() < MIN_TITLE) {
            throw new InvalidOutputException("the title was shorter than " + MIN_TITLE + " characters");
        }
        return title.length() > MAX_TITLE ? cutAtWord(title, MAX_TITLE) : title;
    }

    /** "ui / ux" -> "UI_UX", " High " -> "HIGH". */
    private static String enumKey(String value) {
        return value.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String cutAtWord(String text, int max) {
        int space = text.lastIndexOf(' ', max);
        return (space > max / 2 ? text.substring(0, space) : text.substring(0, max)).strip();
    }

    private static String allowed(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
    }
}
