package com.devassisthub.bug.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devassisthub.bug.dto.BugRequest;
import com.devassisthub.bug.dto.BugResponse;
import com.devassisthub.bug.dto.BugStats;
import com.devassisthub.bug.dto.TriageResponse;
import com.devassisthub.bug.entity.Bug;
import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.BugStatus;
import com.devassisthub.bug.entity.Severity;
import com.devassisthub.bug.exception.ApiException;
import com.devassisthub.bug.repository.BugRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BugServiceTest {

    private final BugRepository repository = mock(BugRepository.class);
    private final BugService service = new BugService(repository);

    @BeforeEach
    void setUp() {
        when(repository.save(any(Bug.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static BugRequest request(Severity severity, BugCategory category, BugStatus status, List<String> tags) {
        return new BugRequest("  Cart total wrong  ", " Total ignores discounts ", " ", null, null, null, null, null,
                severity, category, status, "Ali", null, tags);
    }

    private Bug stored(BugStatus status) {
        Bug bug = new Bug();
        bug.setTitle("t");
        bug.setDescription("d");
        bug.changeStatus(status);
        when(repository.findById(1L)).thenReturn(Optional.of(bug));
        return bug;
    }

    @Test
    void createTrimsTextAppliesDefaultsAndNormalizesTags() {
        BugResponse r = service.create(request(null, null, null, List.of("Checkout Flow", "checkout-flow", " UI ")));

        assertThat(r.title()).isEqualTo("Cart total wrong");
        assertThat(r.description()).isEqualTo("Total ignores discounts");
        assertThat(r.stepsToReproduce()).isNull();
        assertThat(r.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(r.category()).isEqualTo(BugCategory.OTHER);
        assertThat(r.status()).isEqualTo(BugStatus.OPEN);
        assertThat(r.tags()).containsExactly("checkout-flow", "ui");
        assertThat(r.ai()).isNull();
        assertThat(r.resolvedAt()).isNull();
    }

    @Test
    void updateKeepsSeverityAndCategoryWhenOmitted() {
        Bug bug = stored(BugStatus.OPEN);
        bug.setSeverity(Severity.CRITICAL);
        bug.setCategory(BugCategory.CRASH);

        BugResponse r = service.update(1L, request(null, null, null, null));

        assertThat(r.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(r.category()).isEqualTo(BugCategory.CRASH);
        assertThat(r.reporter()).isEqualTo("Ali");
    }

    @Test
    void resolvingSetsTheResolutionDateAndReopeningClearsIt() {
        stored(BugStatus.OPEN);

        BugResponse resolved = service.changeStatus(1L, BugStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isNotNull();

        BugResponse closed = service.changeStatus(1L, BugStatus.CLOSED);
        assertThat(closed.resolvedAt()).isEqualTo(resolved.resolvedAt());

        assertThat(service.changeStatus(1L, BugStatus.IN_PROGRESS).resolvedAt()).isNull();
    }

    @Test
    void unknownIdIsNotFound() {
        when(repository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo("Bug 9 not found");
        });
        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(ApiException.class);
        verify(repository, never()).delete(any(Bug.class));
    }

    @Test
    void recordTriageStoresTheSuggestionButNotOverTheUsersValues() {
        Bug bug = stored(BugStatus.OPEN);
        bug.setSeverity(Severity.LOW);

        BugResponse r = service.recordTriage(1L, new TriageResponse(Severity.HIGH, BugCategory.DATA,
                "Discounts dropped from cart total", "Wrong totals.", "org/model", "v1", 0));

        assertThat(r.severity()).isEqualTo(Severity.LOW);
        assertThat(r.ai().severity()).isEqualTo(Severity.HIGH);
        assertThat(r.ai().suggestedTitle()).isEqualTo("Discounts dropped from cart total");
        assertThat(r.ai().promptVersion()).isEqualTo("v1");
        assertThat(r.ai().triagedAt()).isNotNull();
    }

    @Test
    void statsListEveryValueAndCountOpenBugs() {
        when(repository.countByStatus()).thenReturn(List.of(new Object[] {BugStatus.OPEN, 3L},
                new Object[] {BugStatus.IN_PROGRESS, 2L}, new Object[] {BugStatus.CLOSED, 4L}));
        when(repository.countOpenBySeverity()).thenReturn(List.<Object[]>of(new Object[] {Severity.HIGH, 5L}));
        when(repository.countByCategory()).thenReturn(List.of());

        BugStats stats = service.stats();

        assertThat(stats.total()).isEqualTo(9);
        assertThat(stats.open()).isEqualTo(5);
        assertThat(stats.byStatus()).containsEntry("RESOLVED", 0L).hasSize(4);
        assertThat(stats.openBySeverity()).containsEntry("HIGH", 5L).containsEntry("LOW", 0L);
        assertThat(stats.byCategory()).hasSize(BugCategory.values().length).containsValue(0L);
    }
}
