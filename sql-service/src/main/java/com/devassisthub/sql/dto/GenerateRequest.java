package com.devassisthub.sql.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code POST /api/sql/generate} body (FR-13). A missing dialect means {@code postgresql}; any other value is 400. */
public record GenerateRequest(
        @NotBlank @Size(max = 1000) String prompt,
        @Pattern(regexp = "postgresql", message = "only postgresql is supported") String dialect) {

    public String dialectOrDefault() {
        return dialect == null ? "postgresql" : dialect;
    }
}
