package com.devassisthub.sql.controller;

import com.devassisthub.sql.dto.GenerateRequest;
import com.devassisthub.sql.dto.GenerateResponse;
import com.devassisthub.sql.service.GenerationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** FR-13/FR-18: NL prompt to validated read-only SQL. Never saves, never executes. */
@RestController
public class GenerateController {

    private final GenerationService generation;

    public GenerateController(GenerationService generation) {
        this.generation = generation;
    }

    @PostMapping("/api/sql/generate")
    public GenerateResponse generate(@Valid @RequestBody GenerateRequest request) {
        return generation.generate(request);
    }
}
