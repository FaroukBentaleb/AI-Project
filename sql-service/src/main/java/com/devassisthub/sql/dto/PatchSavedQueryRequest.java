package com.devassisthub.sql.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * PATCH body: absent or null fields are left unchanged; supplied fields get create's limits.
 * Required-on-create text fields, when supplied, must not be blank. {@code version} is required.
 */
public record PatchSavedQueryRequest(
        @Pattern(regexp = NOT_BLANK, message = "must not be blank") @Size(max = 200) String title,
        @Pattern(regexp = NOT_BLANK, message = "must not be blank") @Size(max = 2000) String description,
        @Pattern(regexp = NOT_BLANK, message = "must not be blank") @Size(max = 20000) String sqlContent,
        @Size(max = 10) List<@NotNull @Pattern(regexp = NOT_BLANK, message = "must not be blank") @Size(max = 50) String> tags,
        @Size(max = 2000) String notes,
        @Size(max = 20) String dialect,
        @JsonProperty("is_llm_generated") Boolean isLlmGenerated,
        @Size(max = 1000) String originalNlPrompt,
        @NotNull Integer version) {

    /** Matches any string containing a non-whitespace character; null values are not checked by {@code @Pattern}. */
    static final String NOT_BLANK = "(?s).*\\S.*";
}
