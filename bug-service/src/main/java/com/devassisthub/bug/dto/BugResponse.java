package com.devassisthub.bug.dto;

import com.devassisthub.bug.entity.Bug;
import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.BugStatus;
import com.devassisthub.bug.entity.Severity;
import java.time.OffsetDateTime;
import java.util.List;

/** A bug as the API returns it; {@code ai} is null until the bug has been triaged. */
public record BugResponse(Long id, String title, String description, String stepsToReproduce, String expectedResult,
                          String actualResult, String stackTrace, String environment, String appVersion,
                          Severity severity, BugCategory category, BugStatus status, String reporter, String assignee,
                          List<String> tags, AiTriage ai, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          OffsetDateTime resolvedAt) {

    public record AiTriage(Severity severity, BugCategory category, String suggestedTitle, String rationale,
                           String model, String promptVersion, OffsetDateTime triagedAt) {
    }

    public static BugResponse of(Bug b) {
        AiTriage ai = b.getAiTriagedAt() == null ? null : new AiTriage(b.getAiSeverity(), b.getAiCategory(),
                b.getAiTitle(), b.getAiRationale(), b.getAiModel(), b.getAiPromptVersion(), b.getAiTriagedAt());
        return new BugResponse(b.getId(), b.getTitle(), b.getDescription(), b.getStepsToReproduce(),
                b.getExpectedResult(), b.getActualResult(), b.getStackTrace(), b.getEnvironment(), b.getAppVersion(),
                b.getSeverity(), b.getCategory(), b.getStatus(), b.getReporter(), b.getAssignee(), List.copyOf(b.getTags()),
                ai, b.getCreatedAt(), b.getUpdatedAt(), b.getResolvedAt());
    }
}
