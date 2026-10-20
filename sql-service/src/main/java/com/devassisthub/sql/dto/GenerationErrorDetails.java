package com.devassisthub.sql.dto;

import com.devassisthub.sql.validation.Rejection;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** {@code details} of generation errors (addendum §B): all keys are always present.
 * {@code safetyNote} is set on {@code SQL_REJECTED} only (EXPERIENCE.md: shown with every SQL result, accepted or rejected). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GenerationErrorDetails(String rejectedSql, List<Rejection> reasons, String llmRawResponse,
                                     Integer retryAfterSeconds, String safetyNote) {
}
