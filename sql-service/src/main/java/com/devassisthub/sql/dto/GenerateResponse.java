package com.devassisthub.sql.dto;

import java.util.List;

/** FR-18 generation response (addendum §B). {@code examplesUsed} is empty, never absent, when none were used. */
public record GenerateResponse(String sql, String dialect, String source, String originalPrompt,
                               List<ExampleRef> examplesUsed, List<String> warnings, String safetyNote,
                               boolean valid, String promptVersion) {

    public static final String SOURCE_LLM = "llm-generated";

    /** A Saved Query used as an Example. */
    public record ExampleRef(long id, String title) {
    }
}
