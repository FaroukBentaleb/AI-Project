package com.devassisthub.sql.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.rag.ExampleRetriever;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Story 8.4: {@code POST /api/sql/queries/similar} shape, limit and degradation. */
@WebMvcTest(controllers = SimilarQueryController.class)
@Import(GlobalExceptionHandler.class)
class SimilarQueryControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ExampleRetriever retriever;

    @Test
    void returnsIdAndTitleAskingForThree() throws Exception {
        when(retriever.similar(anyString(), eq(3))).thenReturn(List.of(
                new ExampleRetriever.Example(7, "Weekly orders", "i", "SELECT 1")));
        mvc.perform(post("/api/sql/queries/similar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Orders\",\"description\":\"weekly\",\"sql_content\":\"SELECT 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].title").value("Weekly orders"))
                .andExpect(jsonPath("$[0].sql").doesNotExist());
        verify(retriever).similar(eq("Orders\nweekly\nSELECT 1"), eq(3));
    }

    @Test
    void emptyDraftMakesNoCall() throws Exception {
        mvc.perform(post("/api/sql/queries/similar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        verifyNoInteractions(retriever);
    }

    @Test
    void oversizedFieldsAreTruncatedNotRejected() throws Exception {
        String big = "x".repeat(300);
        when(retriever.similar(anyString(), anyInt())).thenReturn(List.of());
        mvc.perform(post("/api/sql/queries/similar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + big + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        verify(retriever).similar(argThat(t -> t.startsWith("x".repeat(200) + "\n")), eq(3));
    }
}
