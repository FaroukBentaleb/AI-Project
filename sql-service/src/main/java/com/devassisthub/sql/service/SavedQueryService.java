package com.devassisthub.sql.service;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.repository.SavedQueryRepository;
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
        return repository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                GlobalExceptionHandler.NOT_FOUND, "Saved query " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Page<SavedQuery> list(Pageable pageable) {
        return repository.findAll(pageable);
    }
}
