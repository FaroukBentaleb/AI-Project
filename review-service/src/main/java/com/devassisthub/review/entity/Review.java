package com.devassisthub.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "review")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_snippet", nullable = false, columnDefinition = "text")
    private String codeSnippet;

    @Column(nullable = false, length = 50)
    private String language;

    @Column(name = "ai_response", nullable = false, columnDefinition = "text")
    private String aiResponse;

    @Column(name = "relevant_standard", nullable = false, columnDefinition = "text")
    private String relevantStandard;

    @Column(name = "relevance_score", nullable = false)
    private double relevanceScore;

    @Column(nullable = false, length = 32)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Review() {
    }

    public Review(String codeSnippet, String language, String aiResponse, String relevantStandard,
                  double relevanceScore, String status) {
        this.codeSnippet = codeSnippet;
        this.language = language;
        this.aiResponse = aiResponse;
        this.relevantStandard = relevantStandard;
        this.relevanceScore = relevanceScore;
        this.status = status;
    }

    public void update(String codeSnippet, String language, String aiResponse, String relevantStandard,
                       double relevanceScore) {
        this.codeSnippet = codeSnippet;
        this.language = language;
        this.aiResponse = aiResponse;
        this.relevantStandard = relevantStandard;
        this.relevanceScore = relevanceScore;
    }

    public Long getId() {
        return id;
    }

    public String getCodeSnippet() {
        return codeSnippet;
    }

    public String getLanguage() {
        return language;
    }

    public String getAiResponse() {
        return aiResponse;
    }

    public String getRelevantStandard() {
        return relevantStandard;
    }

    public double getRelevanceScore() {
        return relevanceScore;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
