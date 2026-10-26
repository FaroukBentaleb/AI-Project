package com.devassisthub.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateReviewRequest(
        @NotBlank @Size(max = 100000) String codeSnippet,
        @NotBlank @Size(max = 50) String language) {
}
