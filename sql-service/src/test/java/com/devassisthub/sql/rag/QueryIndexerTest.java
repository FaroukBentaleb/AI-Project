package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

class QueryIndexerTest {

    QdrantClient qdrant = mock(QdrantClient.class);
    EmbeddingClient embeddings = mock(EmbeddingClient.class);
    CollectionBootstrap bootstrap = mock(CollectionBootstrap.class);
    SavedQueryRepository repository = mock(SavedQueryRepository.class);
    QueryIndexer indexer = new QueryIndexer(qdrant, embeddings, bootstrap, repository);
    float[] vector = {0.1f, 0.2f};

    @BeforeEach
    void up() {
        when(bootstrap.isReady()).thenReturn(true);
        when(embeddings.embed(anyString())).thenReturn(new EmbeddingResult.Embedded(vector));
    }

    private static SavedQuery query(Boolean readOnly) {
        SavedQuery q = new SavedQuery();
        ReflectionTestUtils.setField(q, "id", 7L);
        q.setTitle("t");
        q.setDescription("d");
        q.setSqlContent("SELECT 1");
        q.setTags(List.of("a"));
        q.applyValidation(readOnly != null && readOnly, List.of(), List.of());
        if (readOnly == null) {
            ReflectionTestUtils.setField(q, "readOnly", null);
        }
        return q;
    }

    @Test
    void indexingRule() {
        assertThat(QueryIndexer.shouldBeIndexed(query(true))).isTrue();
        assertThat(QueryIndexer.shouldBeIndexed(query(false))).isFalse();
        assertThat(QueryIndexer.shouldBeIndexed(query(null))).isFalse();
        SavedQuery dep = query(true);
        dep.markDeprecated("r", null, OffsetDateTime.now());
        assertThat(QueryIndexer.shouldBeIndexed(dep)).isFalse();
        SavedQuery del = query(true);
        del.markDeleted(OffsetDateTime.now());
        assertThat(QueryIndexer.shouldBeIndexed(del)).isFalse();
    }

    @Test
    void embeddedTextIncludesPromptOnlyWhenPresent() {
        SavedQuery q = query(true);
        assertThat(QueryIndexer.embeddedText(q)).isEqualTo("t\nd\nSELECT 1");
        q.setOriginalNlPrompt("why");
        assertThat(QueryIndexer.embeddedText(q)).isEqualTo("t\nd\nSELECT 1\nwhy");
    }

    @Test
    void upsertsQualifyingQuery() {
        SavedQuery q = query(true);
        indexer.syncIndex(q);
        verify(qdrant).upsertPoint(eq(7L), eq(vector), anyMap());
        verify(repository).setIndexed(7L, true);
        assertThat(q.isIndexed()).isTrue();
        assertThat(q.getValidationWarnings()).isEmpty();
    }

    @Test
    void removesNonQualifyingQuery() {
        SavedQuery q = query(false);
        q.setIndexed(true);
        indexer.syncIndex(q);
        verify(qdrant).deletePoint(7L);
        verifyNoInteractions(embeddings);
        verify(repository).setIndexed(7L, false);
        assertThat(q.isIndexed()).isFalse();
    }

    @Test
    void embeddingUnavailableGivesWarningNoUpsert() {
        when(embeddings.embed(anyString())).thenReturn(
                EmbeddingResult.unavailable(EmbeddingResult.Cause.TIMEOUT, "slow"));
        SavedQuery q = query(true);
        indexer.syncIndex(q);
        verify(qdrant, never()).upsertPoint(anyLong(), any(), anyMap());
        verify(repository).setIndexed(7L, false);
        assertThat(q.getValidationWarnings()).singleElement().asString()
                .startsWith(QueryIndexer.WARNING_PREFIX).contains("TIMEOUT");
    }

    @Test
    void qdrantFailureGivesWarning() {
        doThrow(new ResourceAccessException("down")).when(qdrant).upsertPoint(anyLong(), any(), anyMap());
        SavedQuery q = query(true);
        indexer.syncIndex(q);
        assertThat(q.isIndexed()).isFalse();
        assertThat(q.getValidationWarnings()).singleElement().asString().startsWith(QueryIndexer.WARNING_PREFIX);
    }

    @Test
    void collectionNotReadySkipsEmbedding() {
        when(bootstrap.isReady()).thenReturn(false);
        SavedQuery q = query(true);
        indexer.syncIndex(q);
        verifyNoInteractions(embeddings);
        assertThat(q.getValidationWarnings()).hasSize(1);
    }

    @Test
    void removeFailureGivesRemoveWarning() {
        doThrow(new ResourceAccessException("down")).when(qdrant).deletePoint(anyLong());
        SavedQuery q = query(false);
        indexer.syncIndex(q);
        assertThat(q.isIndexed()).isFalse();
        assertThat(q.getValidationWarnings()).containsExactly(QueryIndexer.REMOVE_WARNING);
    }
}
