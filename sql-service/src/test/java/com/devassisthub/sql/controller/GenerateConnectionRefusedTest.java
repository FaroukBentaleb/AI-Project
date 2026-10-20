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
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Story 6.2 (FR-19): a provider that refuses connections is 503 LLM_UNAVAILABLE. */
@WebMvcTest(controllers = GenerateController.class)
@Import({GlobalExceptionHandler.class, ValidatorConfig.class, LlmConfig.class, GenerationService.class,
        com.devassisthub.sql.support.RagOffTestConfig.class})
class GenerateConnectionRefusedTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort(); // closed again before the request: connection refused
        }
        String url = "http://127.0.0.1:" + port + "/models";
        r.add("groq.api-key", () -> "hf_test_token_refused");
        r.add("groq.base-url", () -> url);
    }

    @Autowired MockMvc mvc;

    @Test
    void refusedConnectionIsUnavailable() throws Exception {
        mvc.perform(post("/api/sql/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"orders per region\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(LlmFailureException.UNAVAILABLE_MESSAGE))
                .andExpect(header().doesNotExist("Retry-After"));
    }
}
