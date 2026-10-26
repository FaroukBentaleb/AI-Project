package com.devassisthub.snippet.service;

import com.devassisthub.snippet.config.ModelProperties;
import org.springframework.stereotype.Component;

/** Cuts the code to the model's budget on a line boundary (same rule as {@code trim_code} in snippet_prompt.py). */
@Component
public class CodeTrimmer {

    static final String CUT_MARKER = "\n... [code truncated]";

    public record Trimmed(String text, boolean truncated) {
    }

    private final int limit;

    public CodeTrimmer(ModelProperties props) {
        this.limit = props.maxCodeChars();
    }

    public Trimmed trim(String code) {
        if (code.length() <= limit) {
            return new Trimmed(code, false);
        }
        String head = code.substring(0, Math.max(0, limit - CUT_MARKER.length()));
        int newline = head.lastIndexOf('\n');
        if (newline > 0) {
            head = head.substring(0, newline);
        }
        return new Trimmed(head + CUT_MARKER, true);
    }
}
