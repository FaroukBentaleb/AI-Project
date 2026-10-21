package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

class ReconcilerTest {

    private final QdrantClient qdrant = mock(QdrantClient.class);
    private final CollectionBootstrap bootstrap = mock(CollectionBootstrap.class);
    private final QueryIndexer indexer = mock(QueryIndexer.class);
    private final SavedQueryRepository repo = mock(SavedQueryRepository.class);
    private final Reconciler reconciler = new Reconciler(qdrant, bootstrap, indexer, repo,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), 2);

    @BeforeEach
    void ready() {
        when(bootstrap.isReady()).thenReturn(true);
        when(qdrant.collection()).thenReturn("sql_embeddings");
        when(qdrant.scrollPointIds(any(), eq(2))).thenReturn(new QdrantClient.IdPage(List.of(), null));
        when(repo.findIndexStateAfter(anyLong(), any(Pageable.class))).thenReturn(List.of());
        when(repo.maxId()).thenReturn(100L);
    }

    @AfterEach
    void stop() {
        reconciler.shutdown();
    }

    private static SavedQuery query(long id, boolean readOnly, boolean deprecated, boolean indexed) {
        SavedQuery q = new SavedQuery();
        ReflectionTestUtils.setField(q, "id", id);
        q.applyValidation(readOnly, List.of(), List.of());
        if (deprecated) {
            q.markDeprecated("old", null, OffsetDateTime.now());
        }
        q.setIndexed(indexed);
        return q;
    }

    private void rows(List<Object[]> all) {
        when(repo.findIndexStateAfter(anyLong(), any(Pageable.class))).thenAnswer(inv -> {
            long after = inv.getArgument(0);
            Pageable p = inv.getArgument(1);
            return all.stream().filter(r -> (Long) r[0] > after).limit(p.getPageSize()).toList();
        });
    }

    /** syncIndex stub: indexed when the row qualifies, unless {@code failing}. */
    private void indexer(boolean failing) {
        doAnswer(inv -> {
            SavedQuery q = inv.getArgument(0);
            q.setIndexed(!failing && QueryIndexer.shouldBeIndexed(q));
            return null;
        }).when(indexer).syncIndex(any());
    }

    @Test
    void notReadySkipsWithoutTouchingQdrant() {
        when(bootstrap.isReady()).thenReturn(false);
        assertThat(reconciler.reconcile().completed()).isFalse();
        verify(qdrant, never()).scrollPointIds(any(), eq(2));
    }

    @Test
    void removesOrphansAcrossPagesButKeepsQualifyingAndFreshIds() {
        when(qdrant.scrollPointIds(null, 2)).thenReturn(new QdrantClient.IdPage(List.of(1L, 2L), 3L));
        when(qdrant.scrollPointIds(3L, 2)).thenReturn(new QdrantClient.IdPage(List.of(3L, 500L), null));
        when(repo.findQualifyingIds(List.of(1L, 2L))).thenReturn(List.of(1L));
        when(repo.findQualifyingIds(List.of(3L, 500L))).thenReturn(List.of());

        Reconciler.Result r = reconciler.reconcile();

        verify(qdrant).deletePoints(List.of(2L));
        verify(qdrant).deletePoints(List.of(3L)); // 500 > maxId: may be committing, left for the next run
        assertThat(r.orphansRemoved()).isEqualTo(2);
        assertThat(r.completed()).isTrue();
    }

    @Test
    void indexesUnindexedAndPointlessRowsAndClearsStaleFlags() {
        when(qdrant.scrollPointIds(null, 2)).thenReturn(new QdrantClient.IdPage(List.of(4L), null));
        when(repo.findQualifyingIds(List.of(4L))).thenReturn(List.of(4L));
        rows(List.of(
                new Object[] {1L, true, false},   // qualifying, not indexed -> index
                new Object[] {2L, true, true},    // qualifying, flagged, point missing -> index
                new Object[] {3L, false, true},   // not qualifying, still flagged -> clear
                new Object[] {4L, true, true},    // in sync -> untouched
                new Object[] {5L, false, false})); // in sync -> untouched
        when(repo.findForIndexing(1L)).thenReturn(Optional.of(query(1, true, false, false)));
        when(repo.findForIndexing(2L)).thenReturn(Optional.of(query(2, true, false, true)));
        when(repo.findForIndexing(3L)).thenReturn(Optional.of(query(3, true, true, true)));
        indexer(false);

        Reconciler.Result r = reconciler.reconcile();

        assertThat(r).isEqualTo(new Reconciler.Result(true, 0, 2, 1, 0));
        verify(indexer, times(3)).syncIndex(any());
        verify(repo, never()).findForIndexing(4L);
        verify(repo, never()).findForIndexing(5L);
    }

    @Test
    void stopsAfterConsecutiveFailuresLeavingRestForNextRun() {
        List<Object[]> many = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            many.add(new Object[] {id, true, false});
            when(repo.findForIndexing(id)).thenReturn(Optional.of(query(id, true, false, false)));
        }
        rows(many);
        indexer(true);

        Reconciler.Result r = reconciler.reconcile();

        assertThat(r.completed()).isFalse();
        assertThat(r.failed()).isEqualTo(Reconciler.MAX_CONSECUTIVE_FAILURES);
        verify(indexer, times(Reconciler.MAX_CONSECUTIVE_FAILURES)).syncIndex(any());
    }

    @Test
    void scrollFailureAbortsBeforeTouchingRows() {
        when(qdrant.scrollPointIds(null, 2)).thenThrow(new ResourceAccessException("down"));
        assertThatThrownBy(reconciler::reconcile).isInstanceOf(ResourceAccessException.class);
        verify(qdrant, never()).deletePoints(anyCollection());
        verify(indexer, never()).syncIndex(any());
    }

    @Test
    void collectionReadyEventQueuesAsyncRun() {
        reconciler.onCollectionReady();
        verify(repo, timeout(5000)).maxId();
    }
}
