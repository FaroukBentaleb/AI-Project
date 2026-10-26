package com.devassisthub.bug.controller;

import com.devassisthub.bug.dto.BugResponse;
import com.devassisthub.bug.dto.TriageRequest;
import com.devassisthub.bug.dto.TriageResponse;
import com.devassisthub.bug.dto.TriageStatus;
import com.devassisthub.bug.service.TriageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The LLM feature: report -> severity, category and suggested title. */
@RestController
@RequestMapping("/api/bugs")
public class TriageController {

    private final TriageService service;

    public TriageController(TriageService service) {
        this.service = service;
    }

    /** Whether a model is configured; never calls the model. */
    @GetMapping("/triage/status")
    public TriageStatus status() {
        return service.status();
    }

    /** Suggests a triage for a report that is not saved yet. Nothing is stored. */
    @PostMapping("/triage")
    public TriageResponse triage(@Valid @RequestBody TriageRequest request) {
        return service.triage(request);
    }

    /** Triage of a stored bug; the suggestion is kept in its {@code ai} block, the user's values are unchanged. */
    @PostMapping("/{id}/triage")
    public BugResponse triageStored(@PathVariable long id) {
        return service.triageStored(id);
    }
}
