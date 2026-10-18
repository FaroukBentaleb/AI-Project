package com.devassisthub.sql.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Create body. Server-managed fields (id, author, timestamps, version) are not part of it and are ignored. */
public record CreateSavedQueryRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 2000) String description,
        @NotBlank @Size(max = 20000) String sqlContent,
        @Size(max = 10) List<@NotBlank @Size(max = 50) String> tags,
        @Size(max = 2000) String notes,
        @Size(max = 20) String dialect,
        @JsonProperty("is_llm_generated") Boolean isLlmGenerated,
        @Size(max = 1000) String originalNlPrompt) {
}
