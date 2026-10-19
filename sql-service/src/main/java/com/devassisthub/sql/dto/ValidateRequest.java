package com.devassisthub.sql.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/sql/validate} body. */
public record ValidateRequest(@NotBlank @Size(max = 20000) String sqlContent) {
}
