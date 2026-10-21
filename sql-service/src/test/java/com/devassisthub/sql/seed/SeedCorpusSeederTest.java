package com.devassisthub.sql.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.rag.CollectionBootstrap;
import com.devassisthub.sql.rag.Reconciler;
import com.devassisthub.sql.repository.SavedQueryRepository;
import com.devassisthub.sql.service.SavedQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;

class SeedCorpusSeederTest {

    private final SavedQueryService service = mock(SavedQueryService.class);
    private final SavedQueryRepository repository = mock(SavedQueryRepository.class);
    private final CollectionBootstrap bootstrap = mock(CollectionBootstrap.class);
    private final Reconciler reconciler = mock(Reconciler.class);
    private final List<SeedEntry> corpus = List.of(
            new SeedEntry("a", "A", "da", "SELECT 1", List.of("t")),
            new SeedEntry("b", "B", "db", "SELECT 2", List.of("t")));
    private final SeedCorpusSeeder seeder =
            new SeedCorpusSeeder(service, repository, bootstrap, reconciler, () -> corpus);

    private static SavedQuery row(boolean indexed) {
        SavedQuery q = new SavedQuery();
        q.applyValidation(true, List.of(), List.of());
        q.setIndexed(indexed);
        return q;
    }

    @Test
    void insertsOnlyMissingKeysThroughTheCreatePathAsSeed() {
        when(repository.existsBySeedKey("a")).thenReturn(true);
        when(service.create(any(), eq("seed"), eq("b"))).thenReturn(row(true));

        assertThat(seeder.seed()).isEqualTo(1);

        verify(bootstrap).tryBootstrap();
        verify(service, never()).create(any(), any(), eq("a"));
        verify(service).create(eq(new CreateSavedQueryRequest("B", "db", "SELECT 2", List.of("t"), null, null,
                false, null)), eq("seed"), eq("b"));
        verify(reconciler, never()).requestAsync();
    }

    @Test
    void secondRunInsertsNothing() {
        when(repository.existsBySeedKey(any())).thenReturn(true);
        assertThat(seeder.seed()).isZero();
        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void concurrentInsertIsSkipped() {
        when(service.create(any(), any(), eq("a"))).thenThrow(new DataIntegrityViolationException("dup"));
        when(service.create(any(), any(), eq("b"))).thenReturn(row(true));
        assertThat(seeder.seed()).isEqualTo(1);
    }

    @Test
    void unindexedSeedsWithReadyCollectionQueueReconciliation() {
        when(service.create(any(), any(), any())).thenReturn(row(false));
        when(bootstrap.isReady()).thenReturn(true);
        seeder.seed();
        verify(reconciler).requestAsync();
    }

    @Test
    void unindexedSeedsWithQdrantDownWaitForTheDelayedBootstrap() {
        when(service.create(any(), any(), any())).thenReturn(row(false));
        when(bootstrap.isReady()).thenReturn(false);
        seeder.seed();
        verify(reconciler, never()).requestAsync();
    }

    @Test
    void failureNeverEscapesTheStartupListener() {
        when(repository.existsBySeedKey(any())).thenThrow(new IllegalStateException("db down"));
        seeder.onReady();
    }

    @Test
    void enabledByDefaultAndDisabledByProperty() {
        ApplicationContextRunner context = new ApplicationContextRunner()
                .withUserConfiguration(SeedCorpusSeeder.class)
                .withBean(SavedQueryService.class, () -> service)
                .withBean(SavedQueryRepository.class, () -> repository)
                .withBean(CollectionBootstrap.class, () -> bootstrap)
                .withBean(Reconciler.class, () -> reconciler);
        context.run(c -> assertThat(c).hasSingleBean(SeedCorpusSeeder.class));
        context.withPropertyValues("sql.seed.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(SeedCorpusSeeder.class));
    }
}
