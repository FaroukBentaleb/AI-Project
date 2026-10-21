package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class CollectionBootstrapTest {

    private final QdrantClient client = mock(QdrantClient.class);
    private final CollectionBootstrap bootstrap = new CollectionBootstrap(client, 384);

    @Test
    void createsMissingCollectionWithConfiguredSize() {
        when(client.getCollection()).thenReturn(Optional.empty());
        when(client.createCollection(384)).thenReturn(true);

        assertThat(bootstrap.tryBootstrap()).isTrue();
        assertThat(bootstrap.isReady()).isTrue();
        verify(client).createCollection(384);
    }

    @Test
    void existingCollectionIsNeverRecreated() {
        when(client.getCollection()).thenReturn(Optional.of(new CollectionInfo("sql_embeddings", 384, "Cosine", 7)));

        assertThat(bootstrap.tryBootstrap()).isTrue();
        verify(client, never()).createCollection(anyInt());
        assertThat(bootstrap.sizeMismatch()).isNull();
    }

    @Test
    void sizeMismatchIsReportedNotFixed() {
        when(client.getCollection()).thenReturn(Optional.of(new CollectionInfo("sql_embeddings", 768, "Cosine", 0)));

        assertThat(bootstrap.tryBootstrap()).isTrue();
        verify(client, never()).createCollection(anyInt());
        assertThat(bootstrap.sizeMismatch()).contains("768").contains("384");
    }

    @Test
    void concurrentCreateCountsAsExisting() {
        when(client.getCollection()).thenReturn(Optional.empty(),
                Optional.of(new CollectionInfo("sql_embeddings", 384, "Cosine", 0)));
        when(client.createCollection(384)).thenReturn(false);

        assertThat(bootstrap.tryBootstrap()).isTrue();
        assertThat(bootstrap.sizeMismatch()).isNull();
    }

    @Test
    void unreachableQdrantIsSwallowedAndRetriedUntilSuccess() {
        when(client.getCollection())
                .thenThrow(new ResourceAccessException("Connection refused"))
                .thenReturn(Optional.empty());
        when(client.createCollection(384)).thenReturn(true);

        bootstrap.onReady();
        assertThat(bootstrap.isReady()).isFalse();

        bootstrap.retry();
        assertThat(bootstrap.isReady()).isTrue();

        bootstrap.retry(); // no-op once ready
        verify(client, times(2)).getCollection();
        verify(client, times(1)).createCollection(384);
    }

    @Test
    void publishesReadyEventOnlyOnFirstSuccess() {
        org.springframework.context.ApplicationEventPublisher events =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        bootstrap.setApplicationEventPublisher(events);
        when(client.collection()).thenReturn("sql_embeddings");
        when(client.getCollection()).thenThrow(new ResourceAccessException("down"))
                .thenReturn(Optional.of(new CollectionInfo("sql_embeddings", 384, "Cosine", 0)));

        assertThat(bootstrap.tryBootstrap()).isFalse();
        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
        assertThat(bootstrap.tryBootstrap()).isTrue();
        assertThat(bootstrap.tryBootstrap()).isTrue();
        verify(events, times(1)).publishEvent(new CollectionReadyEvent("sql_embeddings"));
    }
}
