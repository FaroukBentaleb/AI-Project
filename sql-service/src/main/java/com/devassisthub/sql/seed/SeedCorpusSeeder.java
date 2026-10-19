package com.devassisthub.sql.seed;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.rag.CollectionBootstrap;
import com.devassisthub.sql.rag.Reconciler;
import com.devassisthub.sql.repository.SavedQueryRepository;
import com.devassisthub.sql.service.SavedQueryService;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Story 5.5 (FR-26): inserts every Seed Corpus entry whose {@code seed_key} has no row yet (deleted rows included),
 * through the normal create path, so each seed is validated and indexed like any user query. Existing rows are
 * never touched: user edits and deletes of seeds survive restarts. Runs once the application is ready, after a
 * bootstrap attempt; rows that could not be indexed are picked up by Reconciliation (5.4).
 */
@Component
@ConditionalOnProperty(name = "sql.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedCorpusSeeder {

    private static final Logger log = LoggerFactory.getLogger(SeedCorpusSeeder.class);

    private final SavedQueryService service;
    private final SavedQueryRepository repository;
    private final CollectionBootstrap bootstrap;
    private final Reconciler reconciler;
    private final Supplier<List<SeedEntry>> corpus;

    @Autowired
    public SeedCorpusSeeder(SavedQueryService service, SavedQueryRepository repository,
                            CollectionBootstrap bootstrap, Reconciler reconciler) {
        this(service, repository, bootstrap, reconciler, SeedCorpus::load);
    }

    SeedCorpusSeeder(SavedQueryService service, SavedQueryRepository repository, CollectionBootstrap bootstrap,
                     Reconciler reconciler, Supplier<List<SeedEntry>> corpus) {
        this.service = service;
        this.repository = repository;
        this.bootstrap = bootstrap;
        this.reconciler = reconciler;
        this.corpus = corpus;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            seed();
        } catch (RuntimeException e) {
            // Seeding must never take the service down; the next restart retries the missing entries.
            log.error("Seed Corpus seeding failed: {}", e.getMessage(), e);
        }
    }

    /** Returns the number of seed queries inserted. */
    public int seed() {
        // Idempotent; lets seeds index on the spot instead of waiting for Reconciliation when Qdrant is up.
        bootstrap.tryBootstrap();
        int inserted = 0;
        boolean unindexed = false;
        for (SeedEntry e : corpus.get()) {
            if (repository.existsBySeedKey(e.seedKey())) {
                continue;
            }
            SavedQuery saved;
            try {
                saved = service.create(new CreateSavedQueryRequest(e.title(), e.description(), e.sqlContent(),
                        e.tags(), null, null, false, null), SeedCorpus.AUTHOR, e.seedKey());
            } catch (DataIntegrityViolationException race) {
                log.info("Seed '{}' was inserted concurrently; skipped", e.seedKey());
                continue;
            }
            inserted++;
            if (!Boolean.TRUE.equals(saved.getReadOnly())) {
                log.error("Seed '{}' failed the Read-Only Validator ({}); stored but never indexed",
                        e.seedKey(), saved.getReadOnlyReasons());
            } else if (!saved.isIndexed()) {
                unindexed = true;
            }
        }
        if (inserted > 0) {
            log.info("Seed Corpus: inserted {} seed queries", inserted);
        }
        if (unindexed && bootstrap.isReady()) {
            // Collection is up but embedding failed for some seeds: schedule a catch-up run.
            // When Qdrant is down, the delayed bootstrap triggers Reconciliation instead.
            reconciler.requestAsync();
        }
        return inserted;
    }
}
