package com.devassisthub.sql.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.dto.CreateSavedQueryRequest;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SavedQueryController.class)
@Import({GlobalExceptionHandler.class, CallerIdentity.class})
class SavedQueryControllerTest {

    private static final String MINIMAL = "{\"title\":\"t\",\"description\":\"d\",\"sql_content\":\"SELECT 1\"}";

    @Autowired MockMvc mvc;
    @MockBean SavedQueryService service;

    private static SavedQuery saved(CreateSavedQueryRequest r, String author) {
        SavedQuery q = new SavedQuery();
        ReflectionTestUtils.setField(q, "id", 42L);
        ReflectionTestUtils.setField(q, "version", 0);
        q.setTitle(r.title());
        q.setDescription(r.description());
        q.setSqlContent(r.sqlContent());
        q.setTags(r.tags());
        q.setNotes(r.notes());
        q.setDialect(r.dialect() == null ? "postgresql" : r.dialect());
        q.setLlmGenerated(Boolean.TRUE.equals(r.isLlmGenerated()));
        q.setOriginalNlPrompt(r.originalNlPrompt());
        q.setAuthor(author);
        return q;
    }

    private void echo() {
        when(service.create(any(), any())).thenAnswer(inv -> saved(inv.getArgument(0), inv.getArgument(1)));
    }

    @Test
    void createFullBodyIgnoresServerManagedFields() throws Exception {
        echo();
        mvc.perform(post("/api/sql/queries").header("X-User-Id", "  alice ").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":999,"author":"mallory","version":7,"created_at":"2000-01-01T00:00:00Z",
                                 "updated_at":"2000-01-01T00:00:00Z",
                                 "title":"t","description":"d","sql_content":"SELECT 1","tags":["a","b"],
                                 "notes":"n","dialect":"mysql","is_llm_generated":true,"original_nl_prompt":"p"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/sql/queries/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.author").value("alice"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.title").value("t"))
                .andExpect(jsonPath("$.description").value("d"))
                .andExpect(jsonPath("$.sql_content").value("SELECT 1"))
                .andExpect(jsonPath("$.tags[1]").value("b"))
                .andExpect(jsonPath("$.notes").value("n"))
                .andExpect(jsonPath("$.dialect").value("mysql"))
                .andExpect(jsonPath("$.is_llm_generated").value(true))
                .andExpect(jsonPath("$.original_nl_prompt").value("p"))
                .andExpect(jsonPath("$.created_at").hasJsonPath())
                .andExpect(jsonPath("$.updated_at").hasJsonPath())
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings").isEmpty());
        verify(service).create(any(), eq("alice"));
    }

    @Test
    void createMinimalWithBlankIdentityIsAnonymousWithDefaults() throws Exception {
        echo();
        mvc.perform(post("/api/sql/queries").header("X-User-Id", "   ").contentType(MediaType.APPLICATION_JSON)
                        .content(MINIMAL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author").value("anonymous"))
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.tags").isEmpty())
                .andExpect(jsonPath("$.dialect").value("postgresql"))
                .andExpect(jsonPath("$.is_llm_generated").value(false))
                .andExpect(jsonPath("$.notes").value(nullValue()))
                .andExpect(jsonPath("$.original_nl_prompt").value(nullValue()))
                .andExpect(jsonPath("$.warnings").isArray());
    }

    @Test
    void noIdentityHeaderIsAnonymous() throws Exception {
        echo();
        mvc.perform(post("/api/sql/queries").contentType(MediaType.APPLICATION_JSON).content(MINIMAL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author").value("anonymous"));
    }

    static Stream<Arguments> invalidBodies() {
        String t = "\"title\":\"t\"";
        String d = "\"description\":\"d\"";
        String s = "\"sql_content\":\"SELECT 1\"";
        String base = t + "," + d + "," + s;
        return Stream.of(
                Arguments.of("{" + d + "," + s + "}", "title"),
                Arguments.of("{\"title\":\"\"," + d + "," + s + "}", "title"),
                Arguments.of("{\"title\":\"  \"," + d + "," + s + "}", "title"),
                Arguments.of("{" + t + "," + s + "}", "description"),
                Arguments.of("{" + t + ",\"description\":\"\"," + s + "}", "description"),
                Arguments.of("{" + t + ",\"description\":\" \"," + s + "}", "description"),
                Arguments.of("{" + t + "," + d + "}", "sql_content"),
                Arguments.of("{" + t + "," + d + ",\"sql_content\":\"\"}", "sql_content"),
                Arguments.of("{" + t + "," + d + ",\"sql_content\":\"  \"}", "sql_content"),
                Arguments.of("{\"title\":\"" + "x".repeat(201) + "\"," + d + "," + s + "}", "title"),
                Arguments.of("{" + t + ",\"description\":\"" + "x".repeat(2001) + "\"," + s + "}", "description"),
                Arguments.of("{" + t + "," + d + ",\"sql_content\":\"" + "x".repeat(20001) + "\"}", "sql_content"),
                Arguments.of("{" + base + ",\"tags\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\"]}", "tags"),
                Arguments.of("{" + base + ",\"tags\":[\"" + "x".repeat(51) + "\"]}", "tags"),
                Arguments.of("{" + base + ",\"tags\":[\"ok\",\" \"]}", "tags"),
                Arguments.of("{" + base + ",\"tags\":[\"\"]}", "tags"),
                Arguments.of("{" + base + ",\"dialect\":\"" + "x".repeat(21) + "\"}", "dialect"),
                Arguments.of("{" + base + ",\"notes\":\"" + "x".repeat(2001) + "\"}", "notes"),
                Arguments.of("{" + base + ",\"original_nl_prompt\":\"" + "x".repeat(1001) + "\"}", "original_nl_prompt"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void validationFailuresNameTheField(String body, String field) throws Exception {
        mvc.perform(post("/api/sql/queries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value(field));
        verify(service, never()).create(any(), any());
    }

    @Test
    void boundaryLengthsAccepted() throws Exception {
        echo();
        mvc.perform(post("/api/sql/queries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "x".repeat(200) + "\",\"description\":\"" + "d".repeat(2000)
                                + "\",\"sql_content\":\"" + "s".repeat(20000) + "\",\"tags\":[\"" + "y".repeat(50)
                                + "\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\"],\"dialect\":\""
                                + "z".repeat(20) + "\",\"notes\":\"" + "n".repeat(2000)
                                + "\",\"original_nl_prompt\":\"" + "p".repeat(1000) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void identityTooLongRejectedAndNothingSaved() throws Exception {
        mvc.perform(post("/api/sql/queries").header("X-User-Id", "u".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON).content(MINIMAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("x_user_id"));
        verify(service, never()).create(any(), any());
    }

    @Test
    void identityOf100CharsAfterTrimAccepted() throws Exception {
        echo();
        mvc.perform(post("/api/sql/queries").header("X-User-Id", "  " + "u".repeat(100) + "  ")
                        .contentType(MediaType.APPLICATION_JSON).content(MINIMAL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author").value("u".repeat(100)));
    }

    @Test
    void viewExisting() throws Exception {
        when(service.get(42L)).thenReturn(saved(
                new CreateSavedQueryRequest("t", "d", "SELECT 1", List.of("a"), null, null, null, null), "bob"));
        mvc.perform(get("/api/sql/queries/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.author").value("bob"))
                .andExpect(jsonPath("$.sql_content").value("SELECT 1"))
                .andExpect(jsonPath("$.tags[0]").value("a"))
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void viewUnknownIs404() throws Exception {
        when(service.get(7L)).thenThrow(
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Saved query 7 not found"));
        mvc.perform(get("/api/sql/queries/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void viewNonNumericIs400() throws Exception {
        mvc.perform(get("/api/sql/queries/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("id"));
    }
}
