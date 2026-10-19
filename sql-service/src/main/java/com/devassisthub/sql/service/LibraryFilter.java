package com.devassisthub.sql.service;

import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Validated search/filter parameters for the library list (FR-4). Null members mean "no filter".
 * All present filters combine with AND.
 */
public record LibraryFilter(String q, List<String> tags, String author, Boolean llmGenerated, Boolean readOnly,
                            Boolean deprecated) {

    public static final int MAX_Q_LENGTH = 200;
    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG_LENGTH = 50;
    public static final int MAX_AUTHOR_LENGTH = 100;

    public static final LibraryFilter NONE = new LibraryFilter(null, null, null, null, null, null);

    /** Parses raw request params; blank values mean "no filter"; invalid values throw 400 VALIDATION_FAILED. */
    public static LibraryFilter of(String q, String tags, String author, String llmGenerated,
                                   String readOnly, String deprecated) {
        String query = blankToNull(q);
        if (query != null && query.length() > MAX_Q_LENGTH) {
            throw invalid("q", "q: must be at most " + MAX_Q_LENGTH + " characters");
        }
        List<String> tagList = null;
        if (tags != null) {
            tagList = Arrays.stream(tags.split(",")).map(String::trim).filter(t -> !t.isEmpty()).distinct().toList();
            if (tagList.size() > MAX_TAGS) {
                throw invalid("tags", "tags: at most " + MAX_TAGS + " tags");
            }
            if (tagList.stream().anyMatch(t -> t.length() > MAX_TAG_LENGTH)) {
                throw invalid("tags", "tags: each tag must be at most " + MAX_TAG_LENGTH + " characters");
            }
            if (tagList.isEmpty()) {
                tagList = null;
            }
        }
        String a = blankToNull(author);
        if (a != null && a.length() > MAX_AUTHOR_LENGTH) {
            throw invalid("author", "author: must be at most " + MAX_AUTHOR_LENGTH + " characters");
        }
        return new LibraryFilter(query, tagList, a, parseBoolean("llm_generated", llmGenerated),
                parseBoolean("read_only", readOnly),                 parseBoolean("deprecated", deprecated));
    }

    /** Escapes LIKE wildcards with backslash so user text matches literally. */
    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Boolean parseBoolean(String field, String raw) {
        String v = blankToNull(raw);
        if (v == null) {
            return null;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw invalid(field, field + ": must be true or false");
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.VALIDATION_FAILED, message, field, null);
    }
}
