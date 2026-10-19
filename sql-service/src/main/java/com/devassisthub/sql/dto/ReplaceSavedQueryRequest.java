package com.devassisthub.sql.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** PUT body: full replace, same rules as create plus the required {@code version}. Server-managed fields are ignored. */
public record ReplaceSavedQueryRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 2000) String description,
        @NotBlank @Size(max = 20000) String sqlContent,
        @Size(max = 10) List<@NotBlank @Size(max = 50) String> tags,
        @Size(max = 2000) String notes,
        @Size(max = 20) String dialect,
        @JsonProperty("is_llm_generated") Boolean isLlmGenerated,
        @Size(max = 1000) String originalNlPrompt,
        @NotNull Integer version) {
}
