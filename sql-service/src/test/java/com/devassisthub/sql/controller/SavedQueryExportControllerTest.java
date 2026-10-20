package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.service.LibraryFilter;
import com.devassisthub.sql.service.SavedQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SavedQueryController.class)
@Import({GlobalExceptionHandler.class, CallerIdentity.class})
class SavedQueryExportControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SavedQueryService service;

    @Test
    void returnsPlainTextAttachmentWithSqlFilename() throws Exception {
        SavedQuery q = new SavedQuery();
        q.setTitle("Hi */ there");
        q.setDescription("d");
        q.setAuthor("alice");
        q.setSqlContent("SELECT 1");
        when(service.exportAll(any(), any())).thenReturn(List.of(q));
        mvc.perform(get("/api/sql/queries/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=\"sql-library-\\d{4}-\\d{2}-\\d{2}\\.sql\"")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(containsString("-- Title: Hi * / there\n")))
                .andExpect(content().string(containsString("SELECT 1\n;\n")));
    }

    @Test
    void passesListFiltersAndSort() throws Exception {
        when(service.exportAll(any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/sql/queries/export").param("q", "users").param("tags", "a,b").param("author", "bob")
                        .param("llm_generated", "true").param("read_only", "false").param("deprecated", "false")
                        .param("sort", "title,asc"))
                .andExpect(status().isOk());
        ArgumentCaptor<LibraryFilter> f = ArgumentCaptor.forClass(LibraryFilter.class);
        ArgumentCaptor<Sort> s = ArgumentCaptor.forClass(Sort.class);
        verify(service).exportAll(f.capture(), s.capture());
        assertThat(f.getValue()).isEqualTo(new LibraryFilter("users", List.of("a", "b"), "bob", true, false, false));
        assertThat(s.getValue()).isEqualTo(Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id")));
    }

    @Test
    void defaultSortIsNewestFirst() throws Exception {
        when(service.exportAll(any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/sql/queries/export")).andExpect(status().isOk());
        ArgumentCaptor<Sort> s = ArgumentCaptor.forClass(Sort.class);
        verify(service).exportAll(any(), s.capture());
        assertThat(s.getValue()).isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void invalidFilterIs400() throws Exception {
        mvc.perform(get("/api/sql/queries/export").param("deprecated", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("deprecated"));
        verify(service, never()).exportAll(any(), any());
    }
}
