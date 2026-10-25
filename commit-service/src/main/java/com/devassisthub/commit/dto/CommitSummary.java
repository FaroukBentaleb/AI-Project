package com.devassisthub.commit.dto;

import java.time.OffsetDateTime;

/** One row of the history list: everything except the (large) diff and snapshot. */
public record CommitSummary(Long id, String project, OffsetDateTime createdAt, String message, boolean messageEdited,
                            boolean initial, boolean truncated, int fileCount, int filesChanged) {
}
