package com.devassisthub.sql.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SavedQueryServiceTest {

    SavedQueryRepository repository = mock(SavedQueryRepository.class);
    SavedQueryService service = new SavedQueryService(repository, new com.devassisthub.sql.validation.ReadOnlyValidator(), org.mockito.Mockito.mock(com.devassisthub.sql.rag.QueryIndexer.class));

    @BeforeEach
    void echoSave() {
        when(repository.saveAndFlush(any(SavedQuery.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createsWithAllFields() {
        SavedQuery q = service.create(new CreateSavedQueryRequest("t", "d", "SELECT 1", List.of("a", "b"),
                "n", "mysql", true, "prompt"), "alice");
        assertThat(q.getTitle()).isEqualTo("t");
        assertThat(q.getDescription()).isEqualTo("d");
        assertThat(q.getSqlContent()).isEqualTo("SELECT 1");
        assertThat(q.getTags()).containsExactly("a", "b");
        assertThat(q.getNotes()).isEqualTo("n");
        assertThat(q.getDialect()).isEqualTo("mysql");
        assertThat(q.isLlmGenerated()).isTrue();
        assertThat(q.getOriginalNlPrompt()).isEqualTo("prompt");
        assertThat(q.getAuthor()).isEqualTo("alice");
    }

    @Test
    void appliesDefaults() {
        SavedQuery q = service.create(
                new CreateSavedQueryRequest("t", "d", "SELECT 1", null, null, null, null, null), "anonymous");
        assertThat(q.getTags()).isEmpty();
        assertThat(q.getDialect()).isEqualTo("postgresql");
        assertThat(q.isLlmGenerated()).isFalse();
        assertThat(q.getNotes()).isNull();
        assertThat(q.getOriginalNlPrompt()).isNull();
        assertThat(q.getAuthor()).isEqualTo("anonymous");
    }

    @Test
    void blankDialectDefaultsToPostgresql() {
        for (String blank : new String[] {"", "   "}) {
            SavedQuery q = service.create(
                    new CreateSavedQueryRequest("t", "d", "SELECT 1", null, null, blank, null, null), "a");
            assertThat(q.getDialect()).as("'%s'", blank).isEqualTo("postgresql");
        }
    }

    @Test
    void getReturnsExisting() {
        SavedQuery q = new SavedQuery();
        when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(q));
        assertThat(service.get(1L)).isSameAs(q);
    }

    @Test
    void getUnknownThrows404() {
        when(repository.findByIdAndDeletedFalse(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(9L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getError()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void deleteMarksRowDeletedAndSaves() {
        SavedQuery q = new SavedQuery();
        when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(q));
        service.delete(1L);
        assertThat(q.isDeleted()).isTrue();
        assertThat(q.getDeletedAt()).isNotNull();
        org.mockito.Mockito.verify(repository).saveAndFlush(q);
    }

    @Test
    void deleteUnknownOrAlreadyDeletedThrows404() {
        when(repository.findByIdAndDeletedFalse(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAndFlush(any());
    }
    // --- Story 1.7: deprecate / restore ---

    private static com.devassisthub.sql.dto.DeprecateSavedQueryRequest dep(String reason, Long replacedBy) {
        return new com.devassisthub.sql.dto.DeprecateSavedQueryRequest(reason, replacedBy);
    }

    @Test
    void deprecateSetsFieldsAndSaves() {
        SavedQuery q = new SavedQuery();
        when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(q));
        when(repository.findByIdAndDeletedFalse(2L)).thenReturn(Optional.of(new SavedQuery()));
        service.deprecate(1L, dep("old", 2L));
        assertThat(q.isDeprecated()).isTrue();
        assertThat(q.getDeprecatedReason()).isEqualTo("old");
        assertThat(q.getReplacedBy()).isEqualTo(2L);
        assertThat(q.getDeprecatedAt()).isNotNull();
        org.mockito.Mockito.verify(repository).saveAndFlush(q);
    }

    @Test
    void redeprecateUpdatesReasonAndReplacementKeepsTimestamp() {
        SavedQuery q = new SavedQuery();
        when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(q));
        when(repository.findByIdAndDeletedFalse(2L)).thenReturn(Optional.of(new SavedQuery()));
        service.deprecate(1L, dep("first", 2L));
        java.time.OffsetDateTime at = q.getDeprecatedAt();
        service.deprecate(1L, dep("second", null));
        assertThat(q.getDeprecatedReason()).isEqualTo("second");
        assertThat(q.getReplacedBy()).isNull();
        assertThat(q.getDeprecatedAt()).isEqualTo(at);
    }

    @Test
    void deprecateRejectsSelfUnknownOrDeletedReplacement() {
        SavedQuery q = new SavedQuery();
        when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(q));
        when(repository.findByIdAndDeletedFalse(5L)).thenReturn(Optional.empty());
        for (Long bad : new Long[] {1L, 5L}) {
            assertThatThrownBy(() -> service.deprecate(1L, dep("r", bad)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getError()).isEqualTo("VALIDATION_FAILED");
                        assertThat(e.getField()).isEqualTo("replaced_by");
                    });
        }
        assertThat(q.isDeprecated()).isFalse();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void deprecateOrRestoreUnknownThrows404() {
        when(repository.findByIdAndDeletedFalse(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deprecate(9L, dep("r", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.restore(9L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void restoreClearsFieldsAndSaves() {
        SavedQuery q = new SavedQuery();
        q.markDeprecated("r", 2L, java.time.OffsetDateTime.now());
        when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(q));
        assertThat(service.restore(1L)).isSameAs(q);
        assertThat(q.isDeprecated()).isFalse();
        assertThat(q.getDeprecatedReason()).isNull();
        assertThat(q.getDeprecatedAt()).isNull();
        assertThat(q.getReplacedBy()).isNull();
        org.mockito.Mockito.verify(repository).saveAndFlush(q);
    }

    @Test
    void restoreNonDeprecatedIsNoOp() {
        SavedQuery q = new SavedQuery();
        when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(q));
        assertThat(service.restore(1L)).isSameAs(q);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAndFlush(any());
    }
}
