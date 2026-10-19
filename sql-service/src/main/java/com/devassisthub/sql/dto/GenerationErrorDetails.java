package com.devassisthub.sql.dto;

import com.devassisthub.sql.validation.Rejection;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** {@code details} of generation errors (addendum §B): all four keys are always present. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GenerationErrorDetails(String rejectedSql, List<Rejection> reasons, String llmRawResponse,
                                     Integer retryAfterSeconds) {
}
