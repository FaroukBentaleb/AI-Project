package com.devassisthub.bug.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** A bug report. The {@code ai*} columns hold the last triage suggestion; the user decides the real values. */
@Entity
public class Bug {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String stepsToReproduce;

    @Column(columnDefinition = "text")
    private String expectedResult;

    @Column(columnDefinition = "text")
    private String actualResult;

    @Column(columnDefinition = "text")
    private String stackTrace;

    @Column(length = 200)
    private String environment;

    @Column(length = 50)
    private String appVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity = Severity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BugCategory category = BugCategory.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BugStatus status = BugStatus.OPEN;

    @Column(length = 100)
    private String reporter;

    @Column(length = 100)
    private String assignee;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> tags = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Severity aiSeverity;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BugCategory aiCategory;

    @Column(length = 200)
    private String aiTitle;

    @Column(columnDefinition = "text")
    private String aiRationale;

    @Column(length = 200)
    private String aiModel;

    @Column(length = 20)
    private String aiPromptVersion;

    private OffsetDateTime aiTriagedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    private OffsetDateTime resolvedAt;

    /** Moves the bug to {@code next}, setting the resolution date on the way to done and clearing it on reopen. */
    public void changeStatus(BugStatus next) {
        if (next.isDone() && (resolvedAt == null || !status.isDone())) {
            resolvedAt = OffsetDateTime.now();
        } else if (!next.isDone()) {
            resolvedAt = null;
        }
        status = next;
    }

    public void recordTriage(Severity severity, BugCategory category, String title, String rationale, String model,
                             String promptVersion) {
        this.aiSeverity = severity;
        this.aiCategory = category;
        this.aiTitle = title;
        this.aiRationale = rationale;
        this.aiModel = model;
        this.aiPromptVersion = promptVersion;
        this.aiTriagedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStepsToReproduce() { return stepsToReproduce; }
    public void setStepsToReproduce(String stepsToReproduce) { this.stepsToReproduce = stepsToReproduce; }
    public String getExpectedResult() { return expectedResult; }
    public void setExpectedResult(String expectedResult) { this.expectedResult = expectedResult; }
    public String getActualResult() { return actualResult; }
    public void setActualResult(String actualResult) { this.actualResult = actualResult; }
    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public BugCategory getCategory() { return category; }
    public void setCategory(BugCategory category) { this.category = category; }
    public BugStatus getStatus() { return status; }
    public String getReporter() { return reporter; }
    public void setReporter(String reporter) { this.reporter = reporter; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags); }
    public Severity getAiSeverity() { return aiSeverity; }
    public BugCategory getAiCategory() { return aiCategory; }
    public String getAiTitle() { return aiTitle; }
    public String getAiRationale() { return aiRationale; }
    public String getAiModel() { return aiModel; }
    public String getAiPromptVersion() { return aiPromptVersion; }
    public OffsetDateTime getAiTriagedAt() { return aiTriagedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
}
