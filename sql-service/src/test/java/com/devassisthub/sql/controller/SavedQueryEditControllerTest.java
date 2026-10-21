package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.dto.PatchSavedQueryRequest;
import com.devassisthub.sql.dto.ReplaceSavedQueryRequest;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.service.SavedQueryService;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(controllers = SavedQueryController.class)
@Import({GlobalExceptionHandler.class, CallerIdentity.class})
class SavedQueryEditControllerTest {

    private static final String FULL =
            "{\"title\":\"t\",\"description\":\"d\",\"sql_content\":\"SELECT 1\",\"version\":3}";

    @Autowired MockMvc mvc;
    @MockBean SavedQueryService service;

    private static SavedQuery entity() {
        SavedQuery q = new SavedQuery();
        ReflectionTestUtils.setField(q, "id", 7L);
        ReflectionTestUtils.setField(q, "version", 4);
        q.setTitle("t");
        q.setDescription("d");
        q.setSqlContent("SELECT 1");
        q.setTags(List.of("x"));
        q.setAuthor("alice");
        return q;
    }

    @Test
    void putPassesBodyAndReturnsUpdated() throws Exception {
        when(service.replace(eq(7L), any())).thenReturn(entity());
        mvc.perform(put("/api/sql/queries/7").contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.author").value("alice"))
                .andExpect(jsonPath("$.warnings").isEmpty());
        ArgumentCaptor<ReplaceSavedQueryRequest> c = ArgumentCaptor.forClass(ReplaceSavedQueryRequest.class);
        verify(service).replace(eq(7L), c.capture());
        assertThat(c.getValue().version()).isEqualTo(3);
    }

    static Stream<Arguments> invalidPuts() {
        return Stream.of(
                Arguments.of("{\"title\":\"t\",\"sql_content\":\"SELECT 1\",\"version\":3}", "description"),
                Arguments.of("{\"title\":\"t\",\"description\":\" \",\"sql_content\":\"SELECT 1\",\"version\":3}",
                        "description"),
                Arguments.of("{\"description\":\"d\",\"sql_content\":\"SELECT 1\",\"version\":3}", "title"),
                Arguments.of("{\"title\":\"t\",\"description\":\"d\",\"version\":3}", "sql_content"),
                Arguments.of("{\"title\":\"t\",\"description\":\"d\",\"sql_content\":\"SELECT 1\"}", "version"),
                Arguments.of("{\"title\":\"t\",\"description\":\"d\",\"sql_content\":\"SELECT 1\",\"version\":3,"
                        + "\"tags\":[\"\"]}", "tags"));
    }

    @ParameterizedTest
    @MethodSource("invalidPuts")
    void putInvalidIs400WithoutCallingService(String body, String field) throws Exception {
        mvc.perform(put("/api/sql/queries/7").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value(field));
        verify(service, never()).replace(any(), any());
    }

    @Test
    void patchPassesOnlySuppliedFields() throws Exception {
        when(service.patch(eq(7L), any())).thenReturn(entity());
        mvc.perform(patch("/api/sql/queries/7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"a\"],\"version\":4}"))
                .andExpect(status().isOk());
        ArgumentCaptor<PatchSavedQueryRequest> c = ArgumentCaptor.forClass(PatchSavedQueryRequest.class);
        verify(service).patch(eq(7L), c.capture());
        PatchSavedQueryRequest r = c.getValue();
        assertThat(r.tags()).containsExactly("a");
        assertThat(r.title()).isNull();
        assertThat(r.description()).isNull();
        assertThat(r.version()).isEqualTo(4);
    }

    static Stream<Arguments> invalidPatches() {
        return Stream.of(
                Arguments.of("{\"tags\":[\"a\"]}", "version"),
                Arguments.of("{\"title\":\"  \",\"version\":1}", "title"),
                Arguments.of("{\"title\":\"" + "x".repeat(201) + "\",\"version\":1}", "title"),
                Arguments.of("{\"description\":\"\",\"version\":1}", "description"),
                Arguments.of("{\"sql_content\":\"\\n\",\"version\":1}", "sql_content"),
                Arguments.of("{\"tags\":[\" \"],\"version\":1}", "tags"),
                Arguments.of("{\"tags\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\"],"
                        + "\"version\":1}", "tags"),
                Arguments.of("{\"dialect\":\"" + "x".repeat(21) + "\",\"version\":1}", "dialect"),
                Arguments.of("{\"version\":\"abc\"}", null),
                Arguments.of("{", null));
    }

    @ParameterizedTest
    @MethodSource("invalidPatches")
    void patchInvalidIs400WithoutCallingService(String body, String field) throws Exception {
        ResultActions r = mvc.perform(patch("/api/sql/queries/7").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        if (field != null) {
            r.andExpect(jsonPath("$.field").value(field));
        }
        verify(service, never()).patch(any(), any());
    }

    @Test
    void nonNumericIdIs400() throws Exception {
        mvc.perform(put("/api/sql/queries/abc").contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("id"));
    }

    @Test
    void staleVersionFromServiceIs409() throws Exception {
        when(service.patch(eq(7L), any())).thenThrow(SavedQueryService.staleVersion(7L));
        mvc.perform(patch("/api/sql/queries/7").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.field").value("version"));
    }

    @Test
    void lostOptimisticLockRaceIs409() throws Exception {
        when(service.replace(eq(7L), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(SavedQuery.class, 7L));
        mvc.perform(put("/api/sql/queries/7").contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        when(service.replace(eq(9L), any())).thenThrow(
                new ApiException(HttpStatus.NOT_FOUND, GlobalExceptionHandler.NOT_FOUND, "Saved query 9 not found"));
        mvc.perform(put("/api/sql/queries/9").contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
