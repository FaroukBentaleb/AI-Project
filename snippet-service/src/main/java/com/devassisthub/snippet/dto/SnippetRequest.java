package com.devassisthub.snippet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of create and update. {@code docstring} is optional text written by a person; on create, {@code generate}
 * asks the model to write one instead (ignored when a docstring is given).
 */
public record SnippetRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Pattern(regexp = "python|java|javascript", message = "must be python, java or javascript")
        String language,
        @NotBlank @Size(max = 20000) String code,
        @Size(max = 4000) String docstring,
        Boolean generate) {
}
