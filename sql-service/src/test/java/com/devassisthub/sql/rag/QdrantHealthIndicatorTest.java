package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.client.ResourceAccessException;

class QdrantHealthIndicatorTest {

    private final QdrantClient client = mock(QdrantClient.class);
    private final CollectionBootstrap bootstrap = mock(CollectionBootstrap.class);
    private final QdrantHealthIndicator indicator = new QdrantHealthIndicator(client, bootstrap);

    @org.junit.jupiter.api.BeforeEach
    void collectionName() {
        when(client.collection()).thenReturn("sql_embeddings");
    }

    @Test
    void upWithCollectionDetails() {
        when(client.collection()).thenReturn("sql_embeddings");
        when(client.getCollection()).thenReturn(Optional.of(new CollectionInfo("sql_embeddings", 384, "Cosine", 3)));

        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("vector_size", 384).containsEntry("collection", "sql_embeddings");
    }

    @Test
    void downWhenUnreachable() {
        when(client.getCollection()).thenThrow(new ResourceAccessException("Connection refused"));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void downWhenCollectionMissing() {
        when(client.getCollection()).thenReturn(Optional.empty());
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void downOnSizeMismatch() {
        when(client.getCollection()).thenReturn(Optional.of(new CollectionInfo("sql_embeddings", 768, "Cosine", 0)));
        when(bootstrap.sizeMismatch()).thenReturn("collection size 768 != configured 384");
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsKey("vector_size_mismatch");
    }
}
