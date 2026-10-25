package com.devassisthub.sql.validation;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pre-processing (addendum C): NFKC-normalize, then keep only the first markdown fence body if one exists. */
public final class SqlNormalizer {

    private static final Pattern FENCE = Pattern.compile("```[A-Za-z]*[ \\t]*\\R?(.*?)```", Pattern.DOTALL);

    private SqlNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        // Normalize first so fullwidth characters cannot hide a fence, keyword, or semicolon.
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        Matcher m = FENCE.matcher(text);
        if (m.find()) {
            text = m.group(1);
        }
        return text.strip();
    }
}
