package com.devassisthub.sql.dto;

import com.devassisthub.sql.validation.Rejection;
import com.devassisthub.sql.validation.SafetyNote;
import com.devassisthub.sql.validation.ValidationResult;
import java.util.List;

/** Addendum §B validate response: {@code {accepted, normalized_sql, rejections[{code, reason}], warnings, safety_note}}. */
public record ValidateResponse(boolean accepted, String normalizedSql, List<Rejection> rejections,
                               List<String> warnings, String safetyNote) {

    public static ValidateResponse from(ValidationResult r) {
        return new ValidateResponse(r.accepted(), r.normalizedSql(), r.rejections(), r.warnings(), SafetyNote.TEXT);
    }
}
