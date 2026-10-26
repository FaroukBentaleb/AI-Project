package com.devassisthub.bug.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A bug report to triage without saving it (the create form's "Analyze" button). */
public record TriageRequest(
        @Size(max = 200) String title,
        @NotBlank @Size(min = 10, max = 10000, message = "describe the bug in 10 to 10000 characters") String description,
        @Size(max = 5000) String stepsToReproduce,
        @Size(max = 2000) String expectedResult,
        @Size(max = 2000) String actualResult,
        @Size(max = 20000) String stackTrace,
        @Size(max = 200) String environment,
        @Size(max = 50) String appVersion) {
}
