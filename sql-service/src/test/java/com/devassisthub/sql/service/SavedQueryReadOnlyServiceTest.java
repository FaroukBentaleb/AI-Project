package com.devassisthub.sql.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
import com.devassisthub.sql.dto.PatchSavedQueryRequest;
import com.devassisthub.sql.dto.ReplaceSavedQueryRequest;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.repository.SavedQueryRepository;
import com.devassisthub.sql.validation.ReadOnlyValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Story 4.4: every create and every sql_content change runs the validator; other edits do not. */
class SavedQueryReadOnlyServiceTest {

    SavedQueryRepository repository = mock(SavedQueryRepository.class);
    SavedQueryService service = new SavedQueryService(repository, new ReadOnlyValidator(), org.mockito.Mockito.mock(com.devassisthub.sql.rag.QueryIndexer.class));

    @BeforeEach
    void echoSave() {
        when(repository.saveAndFlush(any(SavedQuery.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CreateSavedQueryRequest create(String sql) {
        return new CreateSavedQueryRequest("t", "d", sql, null, null, null, null, null);
    }

    private SavedQuery existing(String sql) {
        SavedQuery q = service.create(create(sql), "a");
        ReflectionTestUtils.setField(q, "version", 0);
        when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(q));
        return q;
    }

    private static PatchSavedQueryRequest patch(String sql, List<String> tags) {
        return new PatchSavedQueryRequest(null, null, sql, tags, null, null, null, null, 0);
    }

    @Test
    void selectIsReadOnlyWithNoReasons() {
        SavedQuery q = service.create(create("SELECT id FROM orders"), "a");
        assertThat(q.getReadOnly()).isTrue();
        assertThat(q.getReadOnlyReasons()).isEmpty();
        assertThat(q.getValidationWarnings()).isEmpty();
    }

    @Test
    void updateStillSavesWithReadOnlyFalseAndCodedReason() {
        SavedQuery q = service.create(create("UPDATE orders SET status='x'"), "a");
        assertThat(q.getReadOnly()).isFalse();
        assertThat(q.getReadOnlyReasons()).singleElement().asString().startsWith("NON_SELECT_STATEMENT: ");
    }

    @Test
    void storesNormalizedSql() {
        SavedQuery q = service.create(create("```sql\n  ＳＥＬＥＣＴ 1  \n```"), "a");
        assertThat(q.getSqlContent()).isEqualTo("SELECT 1");
        assertThat(q.getReadOnly()).isTrue();
    }

    @Test
    void emptyFenceKeepsRawTextAndIsNotReadOnly() {
        SavedQuery q = service.create(create("``` ```"), "a");
        assertThat(q.getSqlContent()).isEqualTo("``` ```");
        assertThat(q.getReadOnly()).isFalse();
        assertThat(q.getReadOnlyReasons()).singleElement().asString().startsWith("SQL_PARSE_ERROR: ");
    }

    @Test
    void validatorWarningsAreExposedForTheRequest() {
        SavedQuery q = service.create(create("SELECT pg_sleep(60)"), "a");
        assertThat(q.getReadOnly()).isTrue();
        assertThat(q.getValidationWarnings()).hasSize(1);
    }

    @Test
    void patchSqlToDeleteRevalidates() {
        existing("SELECT 1");
        SavedQuery q = service.patch(1L, patch("DELETE FROM orders", null));
        assertThat(q.getReadOnly()).isFalse();
        assertThat(q.getReadOnlyReasons()).singleElement().asString().startsWith("NON_SELECT_STATEMENT");
    }

    @Test
    void patchTagsOnlyDoesNotRevalidate() {
        SavedQuery q = existing("SELECT 1");
        q.applyValidation(true, List.of("sentinel"), List.of());
        SavedQuery p = service.patch(1L, patch(null, List.of("x")));
        assertThat(p.getReadOnlyReasons()).containsExactly("sentinel");
        assertThat(p.getValidationWarnings()).isEmpty();
    }

    @Test
    void replaceRevalidates() {
        existing("UPDATE t SET a=1");
        SavedQuery q = service.replace(1L, new ReplaceSavedQueryRequest("t", "d", "SELECT 2", null, null, null,
                null, null, 0));
        assertThat(q.getReadOnly()).isTrue();
        assertThat(q.getReadOnlyReasons()).isEmpty();
    }
}
