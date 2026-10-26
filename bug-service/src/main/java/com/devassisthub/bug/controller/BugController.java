package com.devassisthub.bug.controller;

import com.devassisthub.bug.dto.BugRequest;
import com.devassisthub.bug.dto.BugResponse;
import com.devassisthub.bug.dto.BugStats;
import com.devassisthub.bug.dto.PageResponse;
import com.devassisthub.bug.dto.StatusRequest;
import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.BugStatus;
import com.devassisthub.bug.entity.Severity;
import com.devassisthub.bug.repository.BugSpecifications.SortOrder;
import com.devassisthub.bug.service.BugService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bugs")
public class BugController {

    private final BugService service;

    public BugController(BugService service) {
        this.service = service;
    }

    /** Paged list; every filter is optional and {@code q} searches the title and description. */
    @GetMapping
    public PageResponse<BugResponse> list(@RequestParam(required = false) BugStatus status,
                                          @RequestParam(required = false) Severity severity,
                                          @RequestParam(required = false) BugCategory category,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "NEWEST") SortOrder sort,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return service.list(status, severity, category, q, sort, page, size);
    }

    @GetMapping("/stats")
    public BugStats stats() {
        return service.stats();
    }

    @GetMapping("/{id}")
    public BugResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BugResponse create(@Valid @RequestBody BugRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public BugResponse update(@PathVariable long id, @Valid @RequestBody BugRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public BugResponse changeStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
