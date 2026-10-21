package com.devassisthub.sql.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class SavedQueryUsageControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SavedQueryService service;

    @Test
    void usageReturns204WithNoBody() throws Exception {
        mvc.perform(post("/api/sql/queries/7/usage"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service).recordUsage(7L);
    }

    @Test
    void bodyIsIgnored() throws Exception {
        mvc.perform(post("/api/sql/queries/7/usage").contentType("application/json").content("{\"x\":1}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void unknownOrDeletedIs404() throws Exception {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, GlobalExceptionHandler.NOT_FOUND, "Saved query 9 not found"))
                .when(service).recordUsage(9L);
        mvc.perform(post("/api/sql/queries/9/usage"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void badIdIs400() throws Exception {
        for (String id : new String[] {"abc", "99999999999999999999"}) {
            mvc.perform(post("/api/sql/queries/" + id + "/usage"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value("id"));
        }
        verify(service, never()).recordUsage(anyLong());
    }
}
