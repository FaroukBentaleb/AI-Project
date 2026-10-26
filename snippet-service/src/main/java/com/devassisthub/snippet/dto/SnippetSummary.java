package com.devassisthub.snippet.dto;

import java.time.OffsetDateTime;

/** One row of the library list: everything except the (large) code. */
public record SnippetSummary(Long id, String title, String language, String docstring, boolean docstringEdited,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
