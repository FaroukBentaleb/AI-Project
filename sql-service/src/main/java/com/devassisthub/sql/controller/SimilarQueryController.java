package com.devassisthub.sql.controller;

import com.devassisthub.sql.dto.SimilarQueriesRequest;
import com.devassisthub.sql.dto.SimilarQueryRef;
import com.devassisthub.sql.rag.ExampleRetriever;
import com.devassisthub.sql.rag.QueryIndexer;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 8.4 (FR34): up to 3 Indexed Queries similar to a draft. Always 200; RAG off, Qdrant or embedding down
 * gives {@code []}. Oversized fields are truncated (create limits), never rejected: this is advisory only.
 */
@RestController
public class SimilarQueryController {

    static final int LIMIT = 3;

    private final ExampleRetriever retriever;

    public SimilarQueryController(ExampleRetriever retriever) {
        this.retriever = retriever;
    }

    @PostMapping("/api/sql/queries/similar")
    public List<SimilarQueryRef> similar(@RequestBody(required = false) SimilarQueriesRequest request) {
        if (request == null) {
            return List.of();
        }
        String title = cap(request.title(), 200);
        String description = cap(request.description(), 2000);
        String sql = cap(request.sqlContent(), 20000);
        if (title.isBlank() && description.isBlank() && sql.isBlank()) {
            return List.of();
        }
        String text = QueryIndexer.embeddedText(title, description, sql, null);
        return retriever.similar(text, LIMIT).stream()
                .map(e -> new SimilarQueryRef(e.id(), e.title()))
                .toList();
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
