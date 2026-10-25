package com.devassisthub.sql.dto;

import com.devassisthub.sql.entity.SavedQuery;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record SavedQueryResponse(
        Long id,
        String title,
        String description,
        String sqlContent,
        List<String> tags,
        String dialect,
        String notes,
        String author,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @JsonProperty("is_llm_generated") boolean isLlmGenerated,
        String originalNlPrompt,
        boolean deprecated,
        String deprecatedReason,
        OffsetDateTime deprecatedAt,
        Long replacedBy,
        Boolean readOnly,
        List<String> readOnlyReasons,
        boolean indexed,
        long useCount,
        OffsetDateTime lastUsedAt,
        Integer version,
        List<String> warnings) {

    public static SavedQueryResponse from(SavedQuery q, List<String> warnings) {
        return new SavedQueryResponse(q.getId(), q.getTitle(), q.getDescription(), q.getSqlContent(),
                List.copyOf(q.getTags()), q.getDialect(), q.getNotes(), q.getAuthor(), q.getCreatedAt(),
                q.getUpdatedAt(), q.isLlmGenerated(), q.getOriginalNlPrompt(), q.isDeprecated(),
                q.getDeprecatedReason(), q.getDeprecatedAt(), q.getReplacedBy(),
                q.getReadOnly(), List.copyOf(q.getReadOnlyReasons()), q.isIndexed(), q.getUseCount(), q.getLastUsedAt(), q.getVersion(),
                warnings == null ? List.of() : List.copyOf(warnings));
    }

    /** Response for a mutation: carries the validator and indexing Warnings raised in this request (addendum §B). */
    public static SavedQueryResponse fromMutation(SavedQuery q) {
        return from(q, q.getValidationWarnings());
    }
}
