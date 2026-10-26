package com.devassisthub.snippet.service;

import com.devassisthub.snippet.dto.SnippetDetail;
import com.devassisthub.snippet.dto.SnippetRequest;
import com.devassisthub.snippet.dto.SnippetSummary;
import com.devassisthub.snippet.entity.Snippet;
import com.devassisthub.snippet.exception.ApiException;
import com.devassisthub.snippet.repository.SnippetRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD of snippets plus the docstring feature. The model call always runs outside a database transaction, and a
 * snippet is only saved once the model has answered (a model failure on create leaves nothing behind).
 */
@Service
public class SnippetService {

    private final SnippetRepository snippets;
    private final ModelClient model;
    private final CodeTrimmer trimmer;

    public SnippetService(SnippetRepository snippets, ModelClient model, CodeTrimmer trimmer) {
        this.snippets = snippets;
        this.model = model;
        this.trimmer = trimmer;
    }

    public SnippetDetail create(SnippetRequest request) {
        Snippet snippet = new Snippet(request.title().strip(), request.language(), request.code());
        if (request.docstring() != null && !request.docstring().isBlank()) {
            snippet.setManualDocstring(request.docstring());
        } else if (Boolean.TRUE.equals(request.generate())) {
            generateInto(snippet);
        }
        return SnippetDetail.of(snippets.save(snippet));
    }

    /** Writes a (new) docstring for a saved snippet with the model, replacing the current one. */
    public SnippetDetail generateDocstring(long id) {
        Snippet snippet = find(id);
        generateInto(snippet);
        return SnippetDetail.of(snippets.save(snippet));
    }

    @Transactional(readOnly = true)
    public List<SnippetSummary> list(String language, String query) {
        return snippets.search(language == null ? "" : language.strip(), query == null ? "" : query.strip());
    }

    @Transactional(readOnly = true)
    public SnippetDetail get(long id) {
        return SnippetDetail.of(find(id));
    }

    @Transactional
    public SnippetDetail update(long id, SnippetRequest request) {
        Snippet snippet = find(id);
        snippet.update(request.title().strip(), request.language(), request.code());
        snippet.setManualDocstring(request.docstring());
        return SnippetDetail.of(snippet);
    }

    public void delete(long id) {
        if (!snippets.existsById(id)) {
            throw ApiException.notFound("Snippet");
        }
        snippets.deleteById(id);
    }

    private void generateInto(Snippet snippet) {
        CodeTrimmer.Trimmed input = trimmer.trim(snippet.getCode());
        snippet.setGeneratedDocstring(model.generate(input.text(), snippet.getLanguage()), input.truncated());
    }

    private Snippet find(long id) {
        return snippets.findById(id).orElseThrow(() -> ApiException.notFound("Snippet"));
    }
}
