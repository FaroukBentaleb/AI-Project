package com.devassisthub.sql.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "saved_query")
public class SavedQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "sql_content", nullable = false, columnDefinition = "text")
    private String sqlContent;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> tags = new ArrayList<>();

    @Column(nullable = false, length = 20)
    private String dialect = "postgresql";

    @Column(columnDefinition = "text")
    private String notes;

    @Column(nullable = false, length = 100, updatable = false)
    private String author;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "is_llm_generated", nullable = false)
    private boolean llmGenerated;

    @Column(name = "original_nl_prompt", columnDefinition = "text")
    private String originalNlPrompt;

    @Version
    @Column(nullable = false)
    private Integer version;

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSqlContent() { return sqlContent; }
    public void setSqlContent(String sqlContent) { this.sqlContent = sqlContent; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags); }
    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public boolean isLlmGenerated() { return llmGenerated; }
    public void setLlmGenerated(boolean llmGenerated) { this.llmGenerated = llmGenerated; }
    public String getOriginalNlPrompt() { return originalNlPrompt; }
    public void setOriginalNlPrompt(String originalNlPrompt) { this.originalNlPrompt = originalNlPrompt; }
    public Integer getVersion() { return version; }
}
