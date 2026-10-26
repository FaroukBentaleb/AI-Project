package com.devassisthub.snippet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A saved function with its (generated or hand-written) docstring. */
@Entity
public class Snippet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 20)
    private String language;

    @Column(nullable = false, columnDefinition = "text")
    private String code;

    /** Plain docstring text (no comment markers); null until one is generated or written. */
    @Column(columnDefinition = "text")
    private String docstring;

    /** True once a person changed the docstring (as opposed to the model's own output). */
    @Column(name = "docstring_edited", nullable = false)
    private boolean docstringEdited;

    /** True when the code was cut to fit the model before the docstring was generated. */
    @Column(nullable = false)
    private boolean truncated;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Snippet() {
    }

    public Snippet(String title, String language, String code) {
        this.title = title;
        this.language = language;
        this.code = code;
    }

    /** Stores a model-written docstring (replaces any previous one). */
    public void setGeneratedDocstring(String docstring, boolean truncated) {
        this.docstring = docstring;
        this.docstringEdited = false;
        this.truncated = truncated;
    }

    /** Stores a docstring written or changed by a person; blank clears it. Unchanged text keeps its flags. */
    public void setManualDocstring(String docstring) {
        String value = docstring == null || docstring.isBlank() ? null : docstring.strip();
        if (!Objects.equals(value, this.docstring)) {
            this.docstring = value;
            this.docstringEdited = value != null;
            this.truncated = false;
        }
    }

    public void update(String title, String language, String code) {
        this.title = title;
        this.language = language;
        this.code = code;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getLanguage() {
        return language;
    }

    public String getCode() {
        return code;
    }

    public String getDocstring() {
        return docstring;
    }

    public boolean isDocstringEdited() {
        return docstringEdited;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
