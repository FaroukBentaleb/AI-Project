package com.devassisthub.sql.validation;

import java.util.List;

/** Outcome of validating one SQL text. {@code normalizedSql} is exactly the text that was parsed. */
public record ValidationResult(boolean accepted, String normalizedSql, List<Rejection> rejections, List<String> warnings) {

    public ValidationResult {
        rejections = List.copyOf(rejections);
        warnings = List.copyOf(warnings);
    }
}
