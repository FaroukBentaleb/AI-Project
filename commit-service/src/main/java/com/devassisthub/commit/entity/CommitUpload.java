package com.devassisthub.commit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** One upload of a project: its file snapshot, the diff against the previous upload, and the generated message. */
@Entity
@jakarta.persistence.Table(name = "commit_upload")
public class CommitUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JSON object {path: content} of the text files in the ZIP; the base of the next upload's diff. */
    @Column(nullable = false, columnDefinition = "text")
    private String snapshot;

    @Column(nullable = false, columnDefinition = "text")
    private String diff;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "message_edited", nullable = false)
    private boolean messageEdited;

    /** True for the first upload of a project (the whole project is the diff). */
    @Column(nullable = false)
    private boolean initial;

    /** True when the diff was cut to fit the model before it was sent. */
    @Column(nullable = false)
    private boolean truncated;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "files_changed", nullable = false)
    private int filesChanged;

    protected CommitUpload() {
    }

    public CommitUpload(Project project, String snapshot, String diff, String message, boolean initial,
                        boolean truncated, int fileCount, int filesChanged) {
        this.project = project;
        this.snapshot = snapshot;
        this.diff = diff;
        this.message = message;
        this.initial = initial;
        this.truncated = truncated;
        this.fileCount = fileCount;
        this.filesChanged = filesChanged;
    }

    public void editMessage(String newMessage) {
        this.message = newMessage;
        this.messageEdited = true;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public String getDiff() {
        return diff;
    }

    public String getMessage() {
        return message;
    }

    public boolean isMessageEdited() {
        return messageEdited;
    }

    public boolean isInitial() {
        return initial;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public int getFileCount() {
        return fileCount;
    }

    public int getFilesChanged() {
        return filesChanged;
    }
}
