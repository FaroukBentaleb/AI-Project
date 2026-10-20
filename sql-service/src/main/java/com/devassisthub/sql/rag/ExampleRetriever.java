package com.devassisthub.sql.rag;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Story 6.4 (FR-14/15/16, FR-36): embeds the NL Prompt, searches {@code sql_embeddings} (top-k, score threshold),
 * and re-reads the hits from PostgreSQL, keeping only rows that still satisfy {@link QueryIndexer#shouldBeIndexed}
 * (a stale point never yields a deprecated, deleted or non-read-only Example). Never throws: any failure degrades
 * to "no examples" plus the RAG-unavailable Warning. Prompt and example text are never logged.
 */
public class ExampleRetriever {

    public static final String NO_SIMILAR_WARNING =
            "no similar queries found — verify table names in the generated SQL";
    public static final String RAG_UNAVAILABLE_WARNING =
            "team examples were unavailable — generated without them; verify table names in the generated SQL";

    private static final Logger log = LoggerFactory.getLogger(ExampleRetriever.class);

    /** One retrieved Indexed Query; {@code intent} is original_nl_prompt, else title — description. */
    public record Example(long id, String title, String intent, String sql) {
    }

    /** Examples in score order (best first) and any RAG Warning. */
    public record Retrieval(List<Example> examples, List<String> warnings) {
        public static Retrieval none() {
            return new Retrieval(List.of(), List.of());
        }
    }

    private final boolean enabled;
    private final EmbeddingClient embedding;
    private final QdrantClient qdrant;
    private final SavedQueryRepository repository;
    private final int topK;
    private final double threshold;

    public ExampleRetriever(boolean enabled, EmbeddingClient embedding, QdrantClient qdrant,
                            SavedQueryRepository repository, int topK, double threshold) {
        this.enabled = enabled;
        this.embedding = embedding;
        this.qdrant = qdrant;
        this.repository = repository;
        this.topK = topK;
        this.threshold = threshold;
    }

    /** RAG off (FR-36); used by slice tests that have no Qdrant or database. */
    public static ExampleRetriever disabled() {
        return new ExampleRetriever(false, null, null, null, 0, 1);
    }

    public Retrieval retrieve(String prompt) {
        if (!enabled) {
            return Retrieval.none();
        }
        try {
            EmbeddingResult r = embedding.embed(prompt);
            if (r instanceof EmbeddingResult.Unavailable u) {
                log.warn("RAG unavailable: embedding failed ({})", u.cause());
                return unavailable();
            }
            float[] vector = ((EmbeddingResult.Embedded) r).vector();
            List<QdrantClient.ScoredId> hits = qdrant.search(vector, topK, threshold);
            List<Example> examples = hits.isEmpty() ? List.of() : qualifying(hits);
            log.info("RAG retrieval: {} hits, {} examples (threshold {})", hits.size(), examples.size(), threshold);
            return examples.isEmpty()
                    ? new Retrieval(List.of(), List.of(NO_SIMILAR_WARNING))
                    : new Retrieval(examples, List.of());
        } catch (RuntimeException e) {
            log.warn("RAG unavailable: {}", e.getClass().getSimpleName());
            return unavailable();
        }
    }

    private List<Example> qualifying(List<QdrantClient.ScoredId> hits) {
        List<Long> ids = hits.stream().map(QdrantClient.ScoredId::id).toList();
        Map<Long, SavedQuery> rows = repository.findAllById(ids).stream()
                .collect(Collectors.toMap(SavedQuery::getId, Function.identity()));
        List<Example> out = new ArrayList<>();
        for (Long id : ids) {
            SavedQuery q = rows.get(id);
            if (q != null && QueryIndexer.shouldBeIndexed(q)) {
                out.add(new Example(id, q.getTitle(), intent(q), q.getSqlContent()));
            }
        }
        return out;
    }

    static String intent(SavedQuery q) {
        String nl = q.getOriginalNlPrompt();
        if (nl != null && !nl.isBlank()) {
            return nl.strip();
        }
        String d = q.getDescription() == null ? "" : q.getDescription().strip();
        return d.isEmpty() ? q.getTitle() : q.getTitle() + " — " + d;
    }

    private static Retrieval unavailable() {
        return new Retrieval(List.of(), List.of(RAG_UNAVAILABLE_WARNING));
    }
}
