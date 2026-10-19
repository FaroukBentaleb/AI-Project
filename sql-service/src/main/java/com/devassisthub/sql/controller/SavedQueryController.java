package com.devassisthub.sql.controller;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
import com.devassisthub.sql.dto.DeprecateSavedQueryRequest;
import com.devassisthub.sql.dto.PageResponse;
import com.devassisthub.sql.dto.PatchSavedQueryRequest;
import com.devassisthub.sql.dto.ReplaceSavedQueryRequest;
import com.devassisthub.sql.dto.SavedQueryResponse;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.service.LibraryFilter;
import com.devassisthub.sql.service.LibraryPaging;
import com.devassisthub.sql.service.SavedQueryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PutMapping("/{id}")
    public SavedQueryResponse replace(@PathVariable("id") Long id, @Valid @RequestBody ReplaceSavedQueryRequest request) {
        return SavedQueryResponse.from(service.replace(id, request), List.of());
    }

    @PatchMapping("/{id}")
    public SavedQueryResponse patch(@PathVariable("id") Long id, @Valid @RequestBody PatchSavedQueryRequest request) {
        return SavedQueryResponse.from(service.patch(id, request), List.of());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/usage")
    public ResponseEntity<Void> recordUsage(@PathVariable("id") Long id) {
        service.recordUsage(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/deprecation")
    public SavedQueryResponse deprecate(@PathVariable("id") Long id,
                                        @Valid @RequestBody DeprecateSavedQueryRequest request) {
        return SavedQueryResponse.from(service.deprecate(id, request), List.of());
    }

    @DeleteMapping("/{id}/deprecation")
    public SavedQueryResponse restore(@PathVariable("id") Long id) {
        return SavedQueryResponse.from(service.restore(id), List.of());
    }

    @GetMapping
    public PageResponse<SavedQueryResponse> list(@RequestParam(name = "page", required = false) Integer page,
                                                 @RequestParam(name = "size", required = false) Integer size,
                                                 @RequestParam(name = "sort", required = false) String sort,
                                                 @RequestParam(name = "q", required = false) String q,
                                                 @RequestParam(name = "tags", required = false) String tags,
                                                 @RequestParam(name = "author", required = false) String author,
                                                 @RequestParam(name = "llm_generated", required = false) String llmGenerated,
                                                 @RequestParam(name = "deprecated", required = false) String deprecated) {
        LibraryFilter filter = LibraryFilter.of(q, tags, author, llmGenerated, deprecated);
        return PageResponse.from(service.list(filter, LibraryPaging.of(page, size, sort)),
                sq -> SavedQueryResponse.from(sq, List.of()));
    }
}
