package com.devassisthub.sql.rag;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Story 5.3 (FR-22..FR-24): keeps one RAG Index point per Indexed Query. {@link #syncIndex} upserts or deletes the
 * point per {@link #shouldBeIndexed}, then stores {@code indexed}. It never throws: any Qdrant or embedding failure
 * leaves {@code indexed = false} and adds a Warning to the query; Reconciliation (5.4) repairs it later.
 */
@Component
public class QueryIndexer {

    private static final Logger log = LoggerFactory.getLogger(QueryIndexer.class);

    /** Prefix of the Warning added when indexing fails; the UI toast keys on a non-empty {@code warnings[]}. */
    public static final String WARNING_PREFIX = "Not yet indexed: team examples unavailable";

    /** Warning when a point could not be removed; Reconciliation removes the orphan later. */
    public static final String REMOVE_WARNING =
            "Not yet removed from team examples: RAG index unreachable; reconciliation will remove it.";

    private final QdrantClient qdrant;
    private final EmbeddingClient embeddings;
    private final CollectionBootstrap bootstrap;
    private final SavedQueryRepository repository;

    public QueryIndexer(QdrantClient qdrant, EmbeddingClient embeddings, CollectionBootstrap bootstrap,
                        SavedQueryRepository repository) {
        this.qdrant = qdrant;
        this.embeddings = embeddings;
        this.bootstrap = bootstrap;
        this.repository = repository;
    }

    /** The indexing rule (addendum §D). A not-yet-backfilled {@code read_only = null} does not qualify. */
    public static boolean shouldBeIndexed(SavedQuery q) {
        return Boolean.TRUE.equals(q.getReadOnly()) && !q.isDeleted() && !q.isDeprecated();
    }

    /** Embedded text: {@code title \n description \n sql_content [\n original_nl_prompt]}. */
    public static String embeddedText(SavedQuery q) {
        StringBuilder sb = new StringBuilder()
                .append(q.getTitle()).append('\n').append(q.getDescription()).append('\n').append(q.getSqlContent());
        if (q.getOriginalNlPrompt() != null && !q.getOriginalNlPrompt().isBlank()) {
            sb.append('\n').append(q.getOriginalNlPrompt());
        }
        return sb.toString();
    }

    /** Payload stored with the point, for Epic 6 retrieval. */
    public static Map<String, Object> payload(SavedQuery q) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", q.getTitle());
        p.put("description", q.getDescription());
        p.put("sql_content", q.getSqlContent());
        p.put("original_nl_prompt", q.getOriginalNlPrompt());
        p.put("tags", List.copyOf(q.getTags()));
        return p;
    }

    /** Must be called after the row is flushed (needs the id). Sets and persists {@code q.indexed}. */
    public void syncIndex(SavedQuery q) {
        boolean indexed = shouldBeIndexed(q) ? upsert(q) : remove(q);
        q.setIndexed(indexed);
        repository.setIndexed(q.getId(), indexed);
    }

    private boolean upsert(SavedQuery q) {
        if (!bootstrap.isReady()) {
            return fail(q, "RAG index not ready", "collection not bootstrapped");
        }
        EmbeddingResult r = embeddings.embed(embeddedText(q));
        if (r instanceof EmbeddingResult.Unavailable u) {
            return fail(q, "embedding " + u.cause(), u.detail());
        }
        try {
            qdrant.upsertPoint(q.getId(), ((EmbeddingResult.Embedded) r).vector(), payload(q));
            return true;
        } catch (RuntimeException e) {
            return fail(q, "RAG index unreachable", e.getMessage());
        }
    }

    private boolean remove(SavedQuery q) {
        try {
            qdrant.deletePoint(q.getId());
        } catch (RuntimeException e) {
            log.warn("Removing saved query {} from the RAG index failed: {}", q.getId(), e.getMessage());
            q.addWarning(REMOVE_WARNING);
        }
        // Not an Indexed Query either way; an orphan point left by a failure is removed by Reconciliation.
        return false;
    }

    private boolean fail(SavedQuery q, String reason, String detail) {
        log.warn("Indexing of saved query {} failed ({}): {}", q.getId(), reason, detail);
        q.addWarning(WARNING_PREFIX + " (" + reason + ").");
        return false;
    }
}
