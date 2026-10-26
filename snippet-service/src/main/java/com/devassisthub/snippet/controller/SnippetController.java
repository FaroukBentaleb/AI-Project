package com.devassisthub.snippet.controller;

import com.devassisthub.snippet.dto.SnippetDetail;
import com.devassisthub.snippet.dto.SnippetRequest;
import com.devassisthub.snippet.dto.SnippetSummary;
import com.devassisthub.snippet.service.SnippetService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/snippets")
public class SnippetController {

    private final SnippetService service;

    public SnippetController(SnippetService service) {
        this.service = service;
    }

    /** Saves a snippet; with {@code "generate": true} the model writes its docstring first. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SnippetDetail create(@Valid @RequestBody SnippetRequest request) {
        return service.create(request);
    }

    /** Library, newest first; optionally filtered by language and by a text found in the title or code. */
    @GetMapping
    public List<SnippetSummary> list(@RequestParam(value = "language", required = false) String language,
                                     @RequestParam(value = "q", required = false) String query) {
        return service.list(language, query);
    }

    @GetMapping("/{id}")
    public SnippetDetail get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public SnippetDetail update(@PathVariable long id, @Valid @RequestBody SnippetRequest request) {
        return service.update(id, request);
    }

    /** (Re)writes the docstring of a saved snippet with the model. */
    @PostMapping("/{id}/docstring")
    public SnippetDetail generateDocstring(@PathVariable long id) {
        return service.generateDocstring(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
