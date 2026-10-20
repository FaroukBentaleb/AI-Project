package com.devassisthub.sql.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.config.LlmConfig;
import com.devassisthub.sql.config.ValidatorConfig;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.exception.LlmFailureException;
import com.devassisthub.sql.service.GenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Story 6.2 (FR-19): no HF token. Generation is 503 LLM_NOT_CONFIGURED without any outbound call; validate works. */
@WebMvcTest(controllers = {GenerateController.class, ValidateController.class})
@Import({GlobalExceptionHandler.class, ValidatorConfig.class, LlmConfig.class, GenerationService.class,
        com.devassisthub.sql.support.RagOffTestConfig.class})
// Port 9 (discard) is closed: an outbound call would surface as LLM_UNAVAILABLE, not LLM_NOT_CONFIGURED.
@TestPropertySource(properties = {"hf.api-token=", "hf.base-url=http://127.0.0.1:9/models"})
class GenerateNoTokenTest {

    @Autowired MockMvc mvc;

    @Test
    void generateIsNotConfigured() throws Exception {
        mvc.perform(post("/api/sql/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"orders per region\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("LLM_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message").value(LlmFailureException.NOT_CONFIGURED_MESSAGE))
                .andExpect(jsonPath("$.details.retry_after_seconds").value((Object) null))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void validateStillWorks() throws Exception {
        mvc.perform(post("/api/sql/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql_content\":\"SELECT 1\"}"))
                .andExpect(status().isOk());
    }
}
