package com.devassisthub.sql.llm;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the SQL out of a model completion (markdown fences, sqlcoder {@code [SQL]...[/SQL]}, surrounding prose).
 * It never judges safety: any statement keyword is kept so the Read-Only Validator sees non-read-only output.
 */
public final class SqlExtractor {

    private static final String KEYWORDS = "SELECT|WITH|INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE"
            + "|COPY|MERGE|CALL|DO|EXPLAIN|VALUES|TABLE|SET|VACUUM|LOCK|REINDEX|CLUSTER|COMMENT|REFRESH|EXECUTE";
    private static final Pattern FENCE = Pattern.compile("```[A-Za-z]*[ \\t]*\\r?\\n?(.*?)```", Pattern.DOTALL);
    private static final Pattern OPEN_FENCE = Pattern.compile("```[A-Za-z]*");
    private static final Pattern END_MARK = Pattern.compile("(?i)\\[/SQL]");
    private static final Pattern START_MARK = Pattern.compile("(?i)\\[SQL]");
    private static final Pattern LINE_START = Pattern.compile("(?im)^[ \\t(]*(" + KEYWORDS + ")\\b");
    private static final Pattern PARAGRAPH_START = Pattern.compile("(?i)^[ \\t(]*(" + KEYWORDS + ")\\b");
    private static final Pattern INLINE_SELECT = Pattern.compile("(?i)\\bSELECT\\b");
    private static final Pattern BLANK_LINE = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n");

    private SqlExtractor() {}

    public static Optional<String> extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw;
        Matcher fence = FENCE.matcher(s);
        if (fence.find()) {
            s = fence.group(1);
        } else {
            s = OPEN_FENCE.matcher(s).replaceAll("\n");
        }
        Matcher end = END_MARK.matcher(s);
        if (end.find()) {
            s = s.substring(0, end.start());
        }
        s = START_MARK.matcher(s).replaceAll("\n");
        Matcher start = LINE_START.matcher(s);
        if (start.find()) {
            s = s.substring(start.start());
        } else {
            Matcher inline = INLINE_SELECT.matcher(s);
            if (!inline.find()) {
                return Optional.empty();
            }
            s = s.substring(inline.start());
        }
        s = dropTrailingProse(s).strip();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    /** After a {@code ;}-terminated paragraph, keep later paragraphs only if they start with an SQL keyword. */
    private static String dropTrailingProse(String s) {
        String[] paragraphs = BLANK_LINE.split(s);
        StringBuilder out = new StringBuilder(paragraphs[0]);
        boolean terminated = paragraphs[0].strip().endsWith(";");
        for (int i = 1; i < paragraphs.length; i++) {
            String p = paragraphs[i];
            if (terminated && !PARAGRAPH_START.matcher(p).find()) {
                break;
            }
            out.append("\n\n").append(p);
            terminated = p.strip().endsWith(";");
        }
        return out.toString();
    }
}
