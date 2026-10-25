package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.rag.QdrantClient.ScoredId;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Story 8.4: {@link ExampleRetriever#similar} with mocked embedding, Qdrant and repository. */
class ExampleRetrieverSimilarTest {

    final EmbeddingClient embedding = mock(EmbeddingClient.class);
    final QdrantClient qdrant = mock(QdrantClient.class);
    final SavedQueryRepository repo = mock(SavedQueryRepository.class);
    final ExampleRetriever retriever = new ExampleRetriever(true, embedding, qdrant, repo, 5, 0.5);
    static final float[] VEC = {0.1f, 0.2f};

    private void embeds() {
        when(embedding.embed(any())).thenReturn(new EmbeddingResult.Embedded(VEC));
    }

    @Test
    void capsAtLimitAfterEligibilityFilterInScoreOrder() {
        embeds();
        when(qdrant.search(VEC, 5, 0.5)).thenReturn(List.of(new ScoredId(9, 0.95), new ScoredId(4, 0.9),
                new ScoredId(3, 0.8), new ScoredId(2, 0.7), new ScoredId(1, 0.6)));
        SavedQuery dep = ExampleRetrieverTest.query(9, "dep");
        dep.markDeprecated("old", null, java.time.OffsetDateTime.now());
        when(repo.findAllById(any())).thenReturn(List.of(dep, ExampleRetrieverTest.query(1, "one"),
                ExampleRetrieverTest.query(2, "two"), ExampleRetrieverTest.query(3, "three"),
                ExampleRetrieverTest.query(4, "four")));

        assertThat(retriever.similar("draft", 3)).extracting(ExampleRetriever.Example::id)
                .containsExactly(4L, 3L, 2L);
    }

    @Test
    void disabledOrBlankMakesNoCalls() {
        ExampleRetriever off = new ExampleRetriever(false, embedding, qdrant, repo, 5, 0.5);
        assertThat(off.similar("draft", 3)).isEmpty();
        assertThat(retriever.similar("  ", 3)).isEmpty();
        verifyNoInteractions(embedding, qdrant, repo);
    }

    @Test
    void embeddingUnavailableGivesEmpty() {
        when(embedding.embed(any())).thenReturn(new EmbeddingResult.Unavailable(EmbeddingResult.Cause.values()[0], null, null));
        assertThat(retriever.similar("draft", 3)).isEmpty();
        verifyNoInteractions(qdrant, repo);
    }

    @Test
    void qdrantFailureGivesEmpty() {
        embeds();
        when(qdrant.search(any(), anyInt(), anyDouble())).thenThrow(new IllegalStateException("down"));
        assertThat(retriever.similar("draft", 3)).isEmpty();
    }

    @Test
    void noHitsGivesEmpty() {
        embeds();
        when(qdrant.search(any(), anyInt(), anyDouble())).thenReturn(List.of());
        assertThat(retriever.similar("draft", 3)).isEmpty();
        verifyNoInteractions(repo);
    }
}
