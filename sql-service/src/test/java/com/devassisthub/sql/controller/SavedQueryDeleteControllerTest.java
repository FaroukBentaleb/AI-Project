package com.devassisthub.sql.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.service.SavedQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SavedQueryController.class)
@Import({GlobalExceptionHandler.class, CallerIdentity.class})
class SavedQueryDeleteControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SavedQueryService service;

    @Test
    void deleteReturns204WithNoBody() throws Exception {
        mvc.perform(delete("/api/sql/queries/7"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service).delete(7L);
    }

    @Test
    void unknownOrAlreadyDeletedIs404() throws Exception {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, GlobalExceptionHandler.NOT_FOUND, "Saved query 9 not found"))
                .when(service).delete(9L);
        mvc.perform(delete("/api/sql/queries/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void nonNumericIdIs400() throws Exception {
        mvc.perform(delete("/api/sql/queries/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("id"));
        verify(service, never()).delete(anyLong());
    }

    @Test
    void overflowingIdIs400() throws Exception {
        mvc.perform(delete("/api/sql/queries/99999999999999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("id"));
        verify(service, never()).delete(anyLong());
    }
}
