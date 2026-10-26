package com.devassisthub.snippet.dto;

import com.devassisthub.snippet.entity.Snippet;
import java.time.OffsetDateTime;

/** A single snippet with its code, as returned by create, get, update and generate. */
public record SnippetDetail(Long id, String title, String language, String code, String docstring,
                            boolean docstringEdited, boolean truncated, OffsetDateTime createdAt,
                            OffsetDateTime updatedAt) {

    public static SnippetDetail of(Snippet s) {
        return new SnippetDetail(s.getId(), s.getTitle(), s.getLanguage(), s.getCode(), s.getDocstring(),
                s.isDocstringEdited(), s.isTruncated(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
