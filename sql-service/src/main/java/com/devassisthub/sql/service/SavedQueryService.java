package com.devassisthub.sql.service;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
import com.devassisthub.sql.dto.DeprecateSavedQueryRequest;
import com.devassisthub.sql.dto.PatchSavedQueryRequest;
import com.devassisthub.sql.dto.ReplaceSavedQueryRequest;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedQueryService {

    public static final String DEFAULT_DIALECT = "postgresql";

    private final SavedQueryRepository repository;

    public SavedQueryService(SavedQueryRepository repository) {
        this.repository = repository;
    }

    /** Single create path; future validate/syncIndex hooks attach here. */
    @Transactional
    public SavedQuery create(CreateSavedQueryRequest request, String author) {
        SavedQuery q = new SavedQuery();
        q.setTitle(request.title());
        q.setDescription(request.description());
        q.setSqlContent(request.sqlContent());
        q.setTags(request.tags());
        q.setNotes(request.notes());
        q.setDialect(request.dialect() == null || request.dialect().isBlank() ? DEFAULT_DIALECT : request.dialect());
        q.setLlmGenerated(Boolean.TRUE.equals(request.isLlmGenerated()));
        q.setOriginalNlPrompt(request.originalNlPrompt());
        q.setAuthor(author);
        return repository.saveAndFlush(q);
    }

    @Transactional(readOnly = true)
    public SavedQuery get(Long id) {
        return repository.findByIdAndDeletedFalse(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                GlobalExceptionHandler.NOT_FOUND, "Saved query " + id + " not found"));
    }

    /** Single full-replace path (PUT); future validate/syncIndex hooks attach here. Author/created_at untouched. */
    @Transactional
    public SavedQuery replace(Long id, ReplaceSavedQueryRequest request) {
        SavedQuery q = lockedForEdit(id, request.version());
        q.setTitle(request.title());
        q.setDescription(request.description());
        q.setSqlContent(request.sqlContent());
        q.setTags(request.tags());
        q.setNotes(request.notes());
        q.setDialect(request.dialect() == null || request.dialect().isBlank() ? DEFAULT_DIALECT : request.dialect());
        q.setLlmGenerated(Boolean.TRUE.equals(request.isLlmGenerated()));
        q.setOriginalNlPrompt(request.originalNlPrompt());
        return repository.saveAndFlush(q);
    }

    /** Single partial-update path (PATCH); only non-null fields change. Future hooks attach here. */
    @Transactional
    public SavedQuery patch(Long id, PatchSavedQueryRequest request) {
        SavedQuery q = lockedForEdit(id, request.version());
        if (request.title() != null) q.setTitle(request.title());
        if (request.description() != null) q.setDescription(request.description());
        if (request.sqlContent() != null) q.setSqlContent(request.sqlContent());
        if (request.tags() != null) q.setTags(request.tags());
        if (request.notes() != null) q.setNotes(request.notes());
        if (request.dialect() != null) {
            q.setDialect(request.dialect().isBlank() ? DEFAULT_DIALECT : request.dialect());
        }
        if (request.isLlmGenerated() != null) q.setLlmGenerated(request.isLlmGenerated());
        if (request.originalNlPrompt() != null) q.setOriginalNlPrompt(request.originalNlPrompt());
        return repository.saveAndFlush(q);
    }

    /** Records one copy of the query (FR8). Unknown or deleted -> 404. */
    @Transactional
    public void recordUsage(Long id) {
        if (repository.recordUsage(id, OffsetDateTime.now()) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, GlobalExceptionHandler.NOT_FOUND,
                    "Saved query " + id + " not found");
        }
    }

    /** Single soft-delete path (FR6); future syncIndex hook attaches here. Already-deleted or unknown -> 404. */
    @Transactional
    public void delete(Long id) {
        SavedQuery q = get(id);
        q.markDeleted(OffsetDateTime.now());
        repository.saveAndFlush(q);
    }

    /**
     * Single deprecate path (FR7); future syncIndex hook attaches here. Idempotent: re-deprecating updates
     * reason and replaced_by. replaced_by must be another live query, else 400 on {@code replaced_by}.
     */
    @Transactional
    public SavedQuery deprecate(Long id, DeprecateSavedQueryRequest request) {
        SavedQuery q = get(id);
        Long replacement = request.replacedBy();
        if (replacement != null) {
            if (replacement.equals(id)) {
                throw invalidReplacement("replaced_by: a query cannot replace itself");
            }
            if (repository.findByIdAndDeletedFalse(replacement).isEmpty()) {
                throw invalidReplacement("replaced_by: saved query " + replacement + " does not exist");
            }
        }
        q.markDeprecated(request.reason(), replacement, OffsetDateTime.now());
        return repository.saveAndFlush(q);
    }

    /** Single restore path (FR7); future syncIndex hook attaches here. Not deprecated -> returned unchanged. */
    @Transactional
    public SavedQuery restore(Long id) {
        SavedQuery q = get(id);
        if (!q.isDeprecated()) {
            return q;
        }
        q.clearDeprecation();
        return repository.saveAndFlush(q);
    }

    private static ApiException invalidReplacement(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.VALIDATION_FAILED, message,
                "replaced_by", null);
    }

    /** Loads the row and rejects a stale version before any change; concurrent races are caught by {@code @Version}. */
    private SavedQuery lockedForEdit(Long id, Integer expectedVersion) {
        SavedQuery q = get(id);
        if (!q.getVersion().equals(expectedVersion)) {
            throw staleVersion(id);
        }
        return q;
    }

    public static ApiException staleVersion(Object id) {
        return new ApiException(HttpStatus.CONFLICT, GlobalExceptionHandler.CONFLICT,
                "Saved query " + id + " was modified by someone else; reload and retry", "version", null);
    }

    @Transactional(readOnly = true)
    public Page<SavedQuery> list(LibraryFilter filter, Pageable pageable) {
        return repository.search(filter, pageable);
    }
}
