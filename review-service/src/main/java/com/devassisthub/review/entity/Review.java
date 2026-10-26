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

    @Column(name = "ai_response", nullable = false, columnDefinition = "text")
    private String aiResponse;

    @Column(nullable = false, length = 32)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Review() {
    }

    public Review(String codeSnippet, String aiResponse, String status) {
        this.codeSnippet = codeSnippet;
        this.aiResponse = aiResponse;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getCodeSnippet() {
        return codeSnippet;
    }

    public String getAiResponse() {
        return aiResponse;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
