package com.devassisthub.sql.llm;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Story 6.3 (FR-17, addendum refusal heuristic): a reply is a refusal when no line starts with a SQL keyword and it
 * contains a refusal marker ("I can't", "I cannot", "not able to", "as an AI", "I won't").
 */
public final class RefusalDetector {

    private static final Pattern SQL_LINE = Pattern.compile("(?im)^[ \\t(`]*(SELECT|WITH|INSERT|UPDATE|DELETE|DROP"
            + "|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|MERGE|EXPLAIN|VALUES|TABLE)\\b");
    private static final String[] MARKERS = {"i can't", "i cannot", "i can not", "not able to", "as an ai",
        "i won't", "i will not", "unable to"};

    private RefusalDetector() {}

    public static boolean isRefusal(String raw) {
        if (raw == null || raw.isBlank() || SQL_LINE.matcher(raw).find()) {
            return false;
        }
        String text = raw.toLowerCase(Locale.ROOT).replace('’', '\'');
        for (String m : MARKERS) {
            if (text.contains(m)) {
                return true;
            }
        }
        return false;
    }
}
