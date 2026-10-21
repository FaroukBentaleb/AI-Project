package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.rag.QdrantClient.ScoredId;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Story 6.4: retrieval with mocked embedding, Qdrant and repository. */
class ExampleRetrieverTest {

    final EmbeddingClient embedding = mock(EmbeddingClient.class);
    final QdrantClient qdrant = mock(QdrantClient.class);
    final SavedQueryRepository repo = mock(SavedQueryRepository.class);
    final ExampleRetriever retriever = new ExampleRetriever(true, embedding, qdrant, repo, 5, 0.5);
    static final float[] VEC = {0.1f, 0.2f};

    static SavedQuery query(long id, String title) {
        SavedQuery q = new SavedQuery();
        ReflectionTestUtils.setField(q, "id", id);
        q.setTitle(title);
        q.setDescription("desc " + id);
        q.setSqlContent("SELECT " + id);
        q.applyValidation(true, List.of(), List.of());
        return q;
    }

    private void embeds() {
        when(embedding.embed(any())).thenReturn(new EmbeddingResult.Embedded(VEC));
    }

    @Test
    void disabledMakesNoCalls() {
        ExampleRetriever off = new ExampleRetriever(false, embedding, qdrant, repo, 5, 0.5);
        assertThat(off.retrieve("weekly orders")).isEqualTo(ExampleRetriever.Retrieval.none());
        verifyNoInteractions(embedding, qdrant, repo);
        assertThat(ExampleRetriever.disabled().retrieve("x").warnings()).isEmpty();
    }

    @Test
    void searchesWithTopKAndThresholdAndKeepsScoreOrder() {
        embeds();
        when(qdrant.search(VEC, 5, 0.5)).thenReturn(List.of(new ScoredId(3, 0.9), new ScoredId(1, 0.7)));
        when(repo.findAllById(List.of(3L, 1L))).thenReturn(List.of(query(1, "one"), query(3, "three")));
        ExampleRetriever.Retrieval r = retriever.retrieve("weekly orders");
        assertThat(r.warnings()).isEmpty();
        assertThat(r.examples()).extracting(ExampleRetriever.Example::id).containsExactly(3L, 1L);
        assertThat(r.examples().get(0).intent()).isEqualTo("three — desc 3");
        assertThat(r.examples().get(0).sql()).isEqualTo("SELECT 3");
        verify(qdrant).search(eq(VEC), eq(5), eq(0.5));
    }

    @Test
    void intentPrefersOriginalPrompt() {
        SavedQuery q = query(1, "t");
        q.setOriginalNlPrompt("  orders per week ");
        assertThat(ExampleRetriever.intent(q)).isEqualTo("orders per week");
    }

    @Test
    void staleHitsForDeprecatedDeletedNonReadOnlyOrMissingRowsAreDropped() {
        embeds();
        SavedQuery dep = query(1, "dep");
        dep.markDeprecated("old", null, OffsetDateTime.now());
        SavedQuery del = query(2, "del");
        del.markDeleted(OffsetDateTime.now());
        SavedQuery rw = query(3, "rw");
        rw.applyValidation(false, List.of("DML"), List.of());
        SavedQuery ok = query(4, "ok");
        when(qdrant.search(any(), anyInt(), anyDouble())).thenReturn(List.of(new ScoredId(1, .9),
                new ScoredId(2, .9), new ScoredId(3, .9), new ScoredId(5, .9), new ScoredId(4, .8)));
        when(repo.findAllById(any())).thenReturn(List.of(dep, del, rw, ok));
        assertThat(retriever.retrieve("p").examples()).extracting(ExampleRetriever.Example::title)
                .containsExactly("ok");
    }

    @Test
    void noHitAboveThresholdGivesNoSimilarWarning() {
        embeds();
        when(qdrant.search(any(), anyInt(), anyDouble())).thenReturn(List.of());
        ExampleRetriever.Retrieval r = retriever.retrieve("p");
        assertThat(r.examples()).isEmpty();
        assertThat(r.warnings()).containsExactly(ExampleRetriever.NO_SIMILAR_WARNING);
        verifyNoInteractions(repo);
    }

    @Test
    void allHitsFilteredGivesNoSimilarWarning() {
        embeds();
        SavedQuery dep = query(1, "dep");
        dep.markDeprecated("old", null, OffsetDateTime.now());
        when(qdrant.search(any(), anyInt(), anyDouble())).thenReturn(List.of(new ScoredId(1, .9)));
        when(repo.findAllById(any())).thenReturn(List.of(dep));
        assertThat(retriever.retrieve("p").warnings()).containsExactly(ExampleRetriever.NO_SIMILAR_WARNING);
    }

    @Test
    void embeddingFailureDegradesWithWarning() {
        when(embedding.embed(any())).thenReturn(
                new EmbeddingResult.Unavailable(EmbeddingResult.Cause.TIMEOUT, "slow", null));
        ExampleRetriever.Retrieval r = retriever.retrieve("p");
        assertThat(r.examples()).isEmpty();
        assertThat(r.warnings()).containsExactly(ExampleRetriever.RAG_UNAVAILABLE_WARNING);
        verifyNoInteractions(qdrant);
    }

    @Test
    void qdrantOrDatabaseFailureDegradesWithWarning() {
        embeds();
        when(qdrant.search(any(), anyInt(), anyDouble())).thenThrow(new ResourceAccessException("down"));
        assertThat(retriever.retrieve("p").warnings()).containsExactly(ExampleRetriever.RAG_UNAVAILABLE_WARNING);

        QdrantClient ok = mock(QdrantClient.class);
        when(ok.search(any(), anyInt(), anyDouble())).thenReturn(List.of(new ScoredId(1, .9)));
        when(repo.findAllById(any())).thenThrow(new IllegalStateException("db"));
        assertThat(new ExampleRetriever(true, embedding, ok, repo, 5, .5).retrieve("p").warnings())
                .containsExactly(ExampleRetriever.RAG_UNAVAILABLE_WARNING);
    }

    @Test
    void stoppedQdrantDegradesWithWarning() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory();
        f.setReadTimeout(Duration.ofSeconds(1));
        QdrantClient down = new QdrantClient(RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .requestFactory(f).build(), "sql_embeddings");
        embeds();
        ExampleRetriever.Retrieval r = new ExampleRetriever(true, embedding, down, repo, 5, .5).retrieve("p");
        assertThat(r.warnings()).containsExactly(ExampleRetriever.RAG_UNAVAILABLE_WARNING);
        assertThat(r.examples()).isEmpty();
    }
}
