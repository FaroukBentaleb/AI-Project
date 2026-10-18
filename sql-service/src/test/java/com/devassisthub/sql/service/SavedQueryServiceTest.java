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
    SavedQueryService service = new SavedQueryService(repository);

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
        when(repository.findById(1L)).thenReturn(Optional.of(q));
        assertThat(service.get(1L)).isSameAs(q);
    }

    @Test
    void getUnknownThrows404() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(9L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getError()).isEqualTo("NOT_FOUND");
                });
    }
}
