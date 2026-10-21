package com.devassisthub.sql.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PUT /{id}/deprecation body: reason required; replaced_by optional (existence checked in the service). */
public record DeprecateSavedQueryRequest(
        @NotBlank @Size(max = 2000) String reason,
        Long replacedBy) {
}
