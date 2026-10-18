package com.devassisthub.sql.controller;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
import com.devassisthub.sql.dto.SavedQueryResponse;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.service.SavedQueryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sql/queries")
public class SavedQueryController {

    static final int MAX_AUTHOR_LENGTH = 100;

    private final SavedQueryService service;
    private final CallerIdentity identity;

    public SavedQueryController(SavedQueryService service, CallerIdentity identity) {
        this.service = service;
        this.identity = identity;
    }

    @PostMapping
    public ResponseEntity<SavedQueryResponse> create(@Valid @RequestBody CreateSavedQueryRequest request) {
        String author = identity.getUserId();
        if (author.length() > MAX_AUTHOR_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.VALIDATION_FAILED,
                    "x_user_id: must be at most " + MAX_AUTHOR_LENGTH + " characters", "x_user_id", null);
        }
        SavedQuery saved = service.create(request, author);
        return ResponseEntity.created(URI.create("/api/sql/queries/" + saved.getId()))
                .body(SavedQueryResponse.from(saved, List.of()));
    }

    @GetMapping("/{id}")
    public SavedQueryResponse get(@PathVariable("id") Long id) {
        return SavedQueryResponse.from(service.get(id), List.of());
    }
}
