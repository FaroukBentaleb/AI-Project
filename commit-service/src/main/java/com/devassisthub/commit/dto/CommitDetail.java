package com.devassisthub.commit.dto;

import com.devassisthub.commit.entity.CommitUpload;
import java.time.OffsetDateTime;

/** A single upload with its diff, as returned by upload, get and edit. */
public record CommitDetail(Long id, String project, OffsetDateTime createdAt, String message, boolean messageEdited,
                           boolean initial, boolean truncated, int fileCount, int filesChanged, String diff) {

    public static CommitDetail of(CommitUpload u) {
        return new CommitDetail(u.getId(), u.getProject().getName(), u.getCreatedAt(), u.getMessage(),
                u.isMessageEdited(), u.isInitial(), u.isTruncated(), u.getFileCount(), u.getFilesChanged(),
                u.getDiff());
    }
}
