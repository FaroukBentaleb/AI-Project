package com.devassisthub.sql.service;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
import com.devassisthub.sql.dto.DeprecateSavedQueryRequest;
import com.devassisthub.sql.dto.PatchSavedQueryRequest;
import com.devassisthub.sql.dto.ReplaceSavedQueryRequest;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.rag.QueryIndexer;
import com.devassisthub.sql.repository.SavedQueryRepository;
import com.devassisthub.sql.validation.ReadOnlyValidator;
import com.devassisthub.sql.validation.ValidationResult;
import java.util.List;
import java.util.Objects;
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
    private final ReadOnlyValidator validator;
    private final QueryIndexer indexer;

    public SavedQueryService(SavedQueryRepository repository, ReadOnlyValidator validator, QueryIndexer indexer) {
        this.repository = repository;
        this.validator = validator;
        this.indexer = indexer;
    }

    /** Embedded and payload fields (FR-5): a change to any of them triggers re-indexing (FR-23). */
    private static List<Object> indexedFields(SavedQuery q) {
        return java.util.Arrays.asList(q.getTitle(), q.getDescription(), q.getSqlContent(), q.getOriginalNlPrompt(),
                List.copyOf(q.getTags()), q.getReadOnly());
    }

    /** Re-applies the indexing rule after an edit when an indexed field changed or the flag is out of step. */
    private SavedQuery syncAfterEdit(SavedQuery saved, List<Object> before) {
        if (!Objects.equals(before, indexedFields(saved))
                || QueryIndexer.shouldBeIndexed(saved) != saved.isIndexed()) {
            indexer.syncIndex(saved);
        }
        return saved;
    }

    /** "CODE: reason" strings as stored in read_only_reasons. Shared with the startup backfill. */
    public static List<String> reasons(ValidationResult r) {
        return r.rejections().stream().map(x -> x.code().name() + ": " + x.reason()).toList();
    }

    /**
     * Runs the Read-Only Validator and stores the normalized text, flag and reasons (FR-1, FR-5). A blank
     * normalized text (e.g. an empty fence) keeps the raw text so sql_content is never emptied.
     */
    private void setSqlAndValidate(SavedQuery q, String raw) {
        ValidationResult r = validator.validate(raw);
        q.setSqlContent(r.normalizedSql().isEmpty() ? raw : r.normalizedSql());
        q.applyValidation(r.accepted(), reasons(r), r.warnings());
    }

    /** Single create path; runs the validator, then syncIndex. */
    @Transactional
    public SavedQuery create(CreateSavedQueryRequest request, String author) {
        return create(request, author, null);
    }

    /** Create path with a Seed Corpus key (Story 5.5); {@code seedKey} is null for every API create. */
    @Transactional
    public SavedQuery create(CreateSavedQueryRequest request, String author, String seedKey) {
        SavedQuery q = new SavedQuery();
        q.setTitle(request.title());
        q.setDescription(request.description());
        setSqlAndValidate(q, request.sqlContent());
        q.setTags(request.tags());
        q.setNotes(request.notes());
        q.setDialect(request.dialect() == null || request.dialect().isBlank() ? DEFAULT_DIALECT : request.dialect());
        q.setLlmGenerated(Boolean.TRUE.equals(request.isLlmGenerated()));
        q.setOriginalNlPrompt(request.originalNlPrompt());
        q.setAuthor(author);
        q.setSeedKey(seedKey);
        SavedQuery saved = repository.saveAndFlush(q);
        indexer.syncIndex(saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public SavedQuery get(Long id) {
        return repository.findByIdAndDeletedFalse(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                GlobalExceptionHandler.NOT_FOUND, "Saved query " + id + " not found"));
    }

    /** Single full-replace path (PUT); runs the validator, then syncIndex. Author/created_at untouched. */
    @Transactional
    public SavedQuery replace(Long id, ReplaceSavedQueryRequest request) {
        SavedQuery q = lockedForEdit(id, request.version());
        List<Object> before = indexedFields(q);
        q.setTitle(request.title());
        q.setDescription(request.description());
        setSqlAndValidate(q, request.sqlContent());
        q.setTags(request.tags());
        q.setNotes(request.notes());
        q.setDialect(request.dialect() == null || request.dialect().isBlank() ? DEFAULT_DIALECT : request.dialect());
        q.setLlmGenerated(Boolean.TRUE.equals(request.isLlmGenerated()));
        q.setOriginalNlPrompt(request.originalNlPrompt());
        return syncAfterEdit(repository.saveAndFlush(q), before);
    }

    /** Single partial-update path (PATCH); only non-null fields change. Re-indexes on indexed-field change. */
    @Transactional
    public SavedQuery patch(Long id, PatchSavedQueryRequest request) {
        SavedQuery q = lockedForEdit(id, request.version());
        List<Object> before = indexedFields(q);
        if (request.title() != null) q.setTitle(request.title());
        if (request.description() != null) q.setDescription(request.description());
        if (request.sqlContent() != null) setSqlAndValidate(q, request.sqlContent());
        if (request.tags() != null) q.setTags(request.tags());
        if (request.notes() != null) q.setNotes(request.notes());
        if (request.dialect() != null) {
            q.setDialect(request.dialect().isBlank() ? DEFAULT_DIALECT : request.dialect());
        }
        if (request.isLlmGenerated() != null) q.setLlmGenerated(request.isLlmGenerated());
        if (request.originalNlPrompt() != null) q.setOriginalNlPrompt(request.originalNlPrompt());
        return syncAfterEdit(repository.saveAndFlush(q), before);
    }

    /** Records one copy of the query (FR8). Unknown or deleted -> 404. */
    @Transactional
    public void recordUsage(Long id) {
        if (repository.recordUsage(id, OffsetDateTime.now()) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, GlobalExceptionHandler.NOT_FOUND,
                    "Saved query " + id + " not found");
        }
    }

    /** Single soft-delete path (FR6); then syncIndex. Already-deleted or unknown -> 404. */
    @Transactional
    public void delete(Long id) {
        SavedQuery q = get(id);
        q.markDeleted(OffsetDateTime.now());
        // 204 has no body: a removal failure is only logged; Reconciliation removes the orphan point.
        indexer.syncIndex(repository.saveAndFlush(q));
    }

    /**
     * Single deprecate path (FR7); then syncIndex. Idempotent: re-deprecating updates
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
        SavedQuery saved = repository.saveAndFlush(q);
        indexer.syncIndex(saved);
        return saved;
    }

    /** Single restore path (FR7); then syncIndex. Not deprecated -> returned unchanged. */
    @Transactional
    public SavedQuery restore(Long id) {
        SavedQuery q = get(id);
        if (!q.isDeprecated()) {
            return q;
        }
        q.clearDeprecation();
        SavedQuery saved = repository.saveAndFlush(q);
        indexer.syncIndex(saved);
        return saved;
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
