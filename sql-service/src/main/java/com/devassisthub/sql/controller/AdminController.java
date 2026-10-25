package com.devassisthub.sql.controller;

import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.rag.CollectionBootstrap;
import com.devassisthub.sql.rag.QdrantClient;
import com.devassisthub.sql.rag.Reconciler;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-25 (Should): on-demand Reconciliation. 202 once queued; 503 when Qdrant cannot be reached. */
@RestController
public class AdminController {

    public static final String RAG_INDEX_UNAVAILABLE = "RAG_INDEX_UNAVAILABLE";

    private final CollectionBootstrap bootstrap;
    private final QdrantClient qdrant;
    private final Reconciler reconciler;

    public AdminController(CollectionBootstrap bootstrap, QdrantClient qdrant, Reconciler reconciler) {
        this.bootstrap = bootstrap;
        this.qdrant = qdrant;
        this.reconciler = reconciler;
    }

    @PostMapping("/api/sql/admin/reindex")
    public ResponseEntity<Map<String, String>> reindex() {
        try {
            if (!bootstrap.tryBootstrap() || qdrant.getCollection().isEmpty()) {
                throw unavailable("collection not available");
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("Qdrant unreachable");
        }
        reconciler.requestAsync();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "accepted"));
    }

    private static ApiException unavailable(String reason) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, RAG_INDEX_UNAVAILABLE,
                "RAG index unavailable: " + reason);
    }
}
