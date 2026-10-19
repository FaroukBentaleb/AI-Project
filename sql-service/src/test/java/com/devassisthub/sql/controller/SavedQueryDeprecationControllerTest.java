package com.devassisthub.sql.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.dto.DeprecateSavedQueryRequest;
import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.service.LibraryFilter;
import com.devassisthub.sql.service.SavedQueryService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SavedQueryController.class)
@Import({GlobalExceptionHandler.class, CallerIdentity.class})
class SavedQueryDeprecationControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SavedQueryService service;

    private static SavedQuery sample() {
        SavedQuery q = new SavedQuery();
        q.setTitle("t");
        q.setDescription("d");
        q.setSqlContent("SELECT 1");
        return q;
    }

    @Test
    void deprecateReturns200WithDeprecationFields() throws Exception {
        SavedQuery q = sample();
        q.markDeprecated("use v2", 3L, OffsetDateTime.parse("2026-10-19T10:00:00Z"));
        when(service.deprecate(eq(7L), any())).thenReturn(q);
        mvc.perform(put("/api/sql/queries/7/deprecation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"use v2\",\"replaced_by\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecated").value(true))
                .andExpect(jsonPath("$.deprecated_reason").value("use v2"))
                .andExpect(jsonPath("$.deprecated_at").exists())
                .andExpect(jsonPath("$.replaced_by").value(3));
        verify(service).deprecate(7L, new DeprecateSavedQueryRequest("use v2", 3L));
    }

    @Test
    void restoreReturns200WithClearedFields() throws Exception {
        when(service.restore(7L)).thenReturn(sample());
        mvc.perform(delete("/api/sql/queries/7/deprecation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecated").value(false))
                .andExpect(jsonPath("$.deprecated_reason").isEmpty())
                .andExpect(jsonPath("$.replaced_by").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":\"   \"}", "{\"reason\":null,\"replaced_by\":2}"})
    void missingOrBlankReasonIs400(String body) throws Exception {
        mvc.perform(put("/api/sql/queries/7/deprecation").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("reason"));
        verify(service, never()).deprecate(anyLong(), any());
    }

    @Test
    void overlongReasonIs400() throws Exception {
        mvc.perform(put("/api/sql/queries/7/deprecation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "r".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        verify(service, never()).deprecate(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"reason\":\"r\",\"replaced_by\":\"abc\"}"})
    void missingOrMalformedBodyIs400(String body) throws Exception {
        mvc.perform(put("/api/sql/queries/7/deprecation").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        verify(service, never()).deprecate(anyLong(), any());
    }

    @Test
    void invalidReplacementFromServiceIs400() throws Exception {
        when(service.deprecate(eq(7L), any())).thenThrow(new ApiException(HttpStatus.BAD_REQUEST,
                GlobalExceptionHandler.VALIDATION_FAILED, "replaced_by: bad", "replaced_by", null));
        mvc.perform(put("/api/sql/queries/7/deprecation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"r\",\"replaced_by\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("replaced_by"));
    }

    @Test
    void unknownIdIs404OnBothEndpoints() throws Exception {
        ApiException nf = new ApiException(HttpStatus.NOT_FOUND, GlobalExceptionHandler.NOT_FOUND, "nf");
        when(service.deprecate(eq(9L), any())).thenThrow(nf);
        when(service.restore(9L)).thenThrow(nf);
        mvc.perform(put("/api/sql/queries/9/deprecation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"r\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(delete("/api/sql/queries/9/deprecation"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "99999999999999999999"})
    void badIdIs400(String id) throws Exception {
        mvc.perform(put("/api/sql/queries/" + id + "/deprecation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
        mvc.perform(delete("/api/sql/queries/" + id + "/deprecation"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
        verify(service, never()).deprecate(anyLong(), any());
        verify(service, never()).restore(anyLong());
    }

    @Test
    void deprecatedFilterIsParsedAndPassed() throws Exception {
        when(service.list(any(), any())).thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(1), 0));
        mvc.perform(get("/api/sql/queries").param("deprecated", "FALSE")).andExpect(status().isOk());
        verify(service).list(eq(new LibraryFilter(null, null, null, null, false)), any());
    }

    @Test
    void invalidDeprecatedFilterIs400() throws Exception {
        mvc.perform(get("/api/sql/queries").param("deprecated", "yes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("deprecated"));
    }
}
