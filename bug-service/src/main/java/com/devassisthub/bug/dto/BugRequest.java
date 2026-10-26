package com.devassisthub.bug.dto;

import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.BugStatus;
import com.devassisthub.bug.entity.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of create and full update. Omitted severity, category and status keep their current value on update and
 * default to MEDIUM, OTHER and OPEN on create.
 */
public record BugRequest(
        @NotBlank @Size(min = 3, max = 200) String title,
        @NotBlank @Size(max = 10000) String description,
        @Size(max = 5000) String stepsToReproduce,
        @Size(max = 2000) String expectedResult,
        @Size(max = 2000) String actualResult,
        @Size(max = 20000) String stackTrace,
        @Size(max = 200) String environment,
        @Size(max = 50) String appVersion,
        Severity severity,
        BugCategory category,
        BugStatus status,
        @Size(max = 100) String reporter,
        @Size(max = 100) String assignee,
        @Size(max = 10, message = "at most 10 tags") List<@NotBlank @Size(max = 30) String> tags) {
}
