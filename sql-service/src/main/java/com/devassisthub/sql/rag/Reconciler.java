package com.devassisthub.sql.rag;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 5.4 (FR-25): brings {@code sql_embeddings} into exact agreement with {@link QueryIndexer#shouldBeIndexed}.
 * Removes orphan points, indexes qualifying rows that are not indexed (or whose point is missing) and clears stale
 * {@code indexed} flags. Runs asynchronously on a single daemon thread, so it never blocks startup or requests;
 * concurrent requests are coalesced into at most one queued run.
 */
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    /** Consecutive per-row failures after which a run stops (embedding outage); the rest waits for the next run. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** Outcome of one run, for logs and tests. */
    public record Result(boolean completed, int orphansRemoved, int indexed, int flagsCleared, int failed) { }

    private final QdrantClient qdrant;
    private final CollectionBootstrap bootstrap;
    private final QueryIndexer indexer;
    private final SavedQueryRepository repository;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-reconciler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pending = new AtomicBoolean(false);

    public Reconciler(QdrantClient qdrant, CollectionBootstrap bootstrap, QueryIndexer indexer,
                      SavedQueryRepository repository, TransactionTemplate tx, int batchSize) {
        this.qdrant = qdrant;
        this.bootstrap = bootstrap;
        this.indexer = indexer;
        this.repository = repository;
        this.tx = tx;
        this.batchSize = batchSize;
    }

    @EventListener(CollectionReadyEvent.class)
    public void onCollectionReady() {
        requestAsync();
    }

    /** Queues a run unless one is already queued. Returns immediately. */
    public void requestAsync() {
        if (pending.compareAndSet(false, true)) {
            executor.execute(() -> {
                pending.set(false);
                try {
                    reconcile();
                } catch (RuntimeException e) {
                    log.warn("Reconciliation failed, will retry on the next trigger: {}", e.getMessage());
                }
            });
        }
    }

    /** One synchronous run. Skipped when the collection is not ready; Qdrant errors abort it (next run retries). */
    public synchronized Result reconcile() {
        if (!bootstrap.isReady()) {
            log.info("Reconciliation skipped: collection '{}' not bootstrapped yet", qdrant.collection());
            return new Result(false, 0, 0, 0, 0);
        }
        long maxId = repository.maxId();
        Set<Long> pointIds = new HashSet<>();
        int orphans = removeOrphans(maxId, pointIds);

        int indexed = 0;
        int cleared = 0;
        int failed = 0;
        int consecutive = 0;
        long after = 0;
        while (true) {
            List<Object[]> rows = repository.findIndexStateAfter(after, PageRequest.of(0, batchSize));
            if (rows.isEmpty()) {
                break;
            }
            for (Object[] row : rows) {
                long id = ((Number) row[0]).longValue();
                after = id;
                boolean qualifies = Boolean.TRUE.equals(row[1]);
                boolean flag = Boolean.TRUE.equals(row[2]);
                boolean needsIndex = qualifies && (!flag || !pointIds.contains(id));
                boolean needsClear = !qualifies && flag;
                if (!needsIndex && !needsClear) {
                    continue;
                }
                Boolean ok = tx.execute(s -> repository.findForIndexing(id).map(q -> {
                    indexer.syncIndex(q);
                    return q.isIndexed() == QueryIndexer.shouldBeIndexed(q);
                }).orElse(true));
                if (Boolean.TRUE.equals(ok)) {
                    consecutive = 0;
                    if (needsIndex) {
                        indexed++;
                    } else {
                        cleared++;
                    }
                } else {
                    failed++;
                    if (++consecutive >= MAX_CONSECUTIVE_FAILURES) {
                        log.warn("Reconciliation stopped after {} consecutive indexing failures; "
                                + "remaining rows are retried on the next run", consecutive);
                        return logged(new Result(false, orphans, indexed, cleared, failed));
                    }
                }
            }
        }
        return logged(new Result(true, orphans, indexed, cleared, failed));
    }

    /** Scrolls every point id, deletes ids without a qualifying row, and collects the ids that remain. */
    private int removeOrphans(long maxId, Set<Long> kept) {
        int removed = 0;
        Long offset = null;
        do {
            QdrantClient.IdPage page = qdrant.scrollPointIds(offset, batchSize);
            Set<Long> qualifying = page.ids().isEmpty() ? Set.of()
                    : new HashSet<>(repository.findQualifyingIds(page.ids()));
            List<Long> orphans = new ArrayList<>();
            for (Long id : page.ids()) {
                if (qualifying.contains(id)) {
                    kept.add(id);
                } else if (id <= maxId) {
                    orphans.add(id);
                }
                // id > maxId: row may be committing right now (create upserts before commit); next run decides.
            }
            qdrant.deletePoints(orphans);
            removed += orphans.size();
            offset = page.nextOffset();
        } while (offset != null);
        return removed;
    }

    private Result logged(Result r) {
        log.info("Reconciliation of '{}' {}: {} orphan point(s) removed, {} indexed, {} flag(s) cleared, {} failed",
                qdrant.collection(), r.completed() ? "completed" : "incomplete", r.orphansRemoved(), r.indexed(),
                r.flagsCleared(), r.failed());
        return r;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
