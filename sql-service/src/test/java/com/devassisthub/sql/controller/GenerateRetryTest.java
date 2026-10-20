package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.config.LlmConfig;
import com.devassisthub.sql.config.ValidatorConfig;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.exception.LlmFailureException;
import com.devassisthub.sql.service.GenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Story 6.3 (FR-17): one corrective retry, refusal detection, deadline guard. Simulated HF only. */
@WebMvcTest(controllers = GenerateController.class)
@Import({GlobalExceptionHandler.class, ValidatorConfig.class, LlmConfig.class, GenerationService.class,
        com.devassisthub.sql.support.RagOffTestConfig.class})
class GenerateRetryTest {

    static final MockWebServer SERVER = start();
    static final ObjectMapper JSON = new ObjectMapper();

    static MockWebServer start() {
        MockWebServer s = new MockWebServer();
        try {
            s.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    @AfterAll
    static void stop() throws IOException {
        SERVER.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("hf.api-token", () -> "hf_test_retry_token");
        r.add("hf.timeout", () -> "1s");
        r.add("hf.base-url", () -> SERVER.url("/models").toString());
    }

    @Autowired MockMvc mvc;

    @BeforeEach
    void drain() throws InterruptedException {
        while (SERVER.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // discard requests left by earlier tests
        }
    }

    static MockResponse text(String generatedText) throws IOException {
        return new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSON.writeValueAsString(new Object[] {Map.of("generated_text", generatedText)}));
    }

    static void reply(String generatedText) throws IOException {
        SERVER.enqueue(text(generatedText));
    }

    static ResultActions generate(MockMvc m, String prompt) throws Exception {
        return m.perform(post("/api/sql/generate").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("prompt", prompt))));
    }

    static String inputs(RecordedRequest req) throws IOException {
        return (String) JSON.readValue(req.getBody().readUtf8(), Map.class).get("inputs");
    }

    static void assertCalls(int n) throws InterruptedException {
        for (int i = 0; i < n; i++) {
            assertThat(SERVER.takeRequest(1, TimeUnit.SECONDS)).as("call " + (i + 1)).isNotNull();
        }
        assertThat(SERVER.takeRequest(200, TimeUnit.MILLISECONDS)).as("no extra call").isNull();
    }

    @Test
    void proseThenSqlSucceedsAfterExactlyTwoCalls() throws Exception {
        reply("Sure! This shows the orders per region.");
        reply("SELECT region, count(*) FROM orders GROUP BY region");
        generate(mvc, "orders per region").andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value("SELECT region, count(*) FROM orders GROUP BY region"))
                .andExpect(jsonPath("$.prompt_version").value("v1"));
        RecordedRequest first = SERVER.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest second = SERVER.takeRequest(1, TimeUnit.SECONDS);
        assertThat(inputs(first)).doesNotContain("Previous answer");
        assertThat(inputs(second)).contains("### Previous answer (not usable)",
                "Sure! This shows the orders per region.", "[QUESTION]orders per region[/QUESTION]");
        assertThat(SERVER.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void unparseableSqlIsRetried() throws Exception {
        reply("SELECT FROM WHERE (((");
        reply("SELECT 1");
        generate(mvc, "one").andExpect(status().isOk()).andExpect(jsonPath("$.sql").value("SELECT 1"));
        assertCalls(2);
    }

    @Test
    void emptyThenSqlSucceeds() throws Exception {
        SERVER.enqueue(new MockResponse().setBody(""));
        reply("SELECT 1");
        generate(mvc, "one").andExpect(status().isOk());
        assertCalls(2);
    }

    @Test
    void twoUnparseableRepliesAreParseErrorWithRawReply() throws Exception {
        reply("first garbage");
        reply("second garbage");
        generate(mvc, "orders").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SQL_PARSE_ERROR"))
                .andExpect(jsonPath("$.message").value(GenerationService.PARSE_ERROR_MESSAGE))
                .andExpect(jsonPath("$.details.llm_raw_response").value("second garbage"))
                .andExpect(jsonPath("$.details.retry_after_seconds", nullValue()));
        assertCalls(2);
    }

    @Test
    void twoEmptyRepliesAreParseError() throws Exception {
        SERVER.enqueue(new MockResponse().setBody(""));
        SERVER.enqueue(new MockResponse().setBody(""));
        generate(mvc, "orders").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SQL_PARSE_ERROR"))
                .andExpect(jsonPath("$.details.llm_raw_response").value(""));
        assertCalls(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"I can't help with dropping tables", "I cannot do that.",
            "As an AI, I won’t delete data."})
    void finalRefusalIsLlmRefused(String refusal) throws Exception {
        reply("I can't help with dropping tables");
        reply(refusal);
        generate(mvc, "drop the orders table").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LLM_REFUSED"))
                .andExpect(jsonPath("$.message").value("The model declined to generate SQL for this request"))
                .andExpect(jsonPath("$.details.llm_raw_response").value(refusal));
        assertCalls(2);
    }

    @Test
    void refusalThenSqlSucceeds() throws Exception {
        reply("Sorry, I am not able to answer that.");
        reply("SELECT 1");
        generate(mvc, "x").andExpect(status().isOk());
        assertCalls(2);
    }

    @Test
    void onlyTheFinalReplyIsClassified() throws Exception {
        reply("I cannot help");
        reply("still nothing useful");
        generate(mvc, "x").andExpect(jsonPath("$.error").value("SQL_PARSE_ERROR"));
        assertCalls(2);
    }

    @Test
    void validatorRejectionIsNotRetried() throws Exception {
        reply("DELETE FROM orders");
        generate(mvc, "x").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SQL_REJECTED"));
        assertCalls(1);
    }

    @Test
    void retryThatReturnsUnsafeSqlIsRejected() throws Exception {
        reply("hmm");
        reply("DROP TABLE orders");
        generate(mvc, "x").andExpect(jsonPath("$.error").value("SQL_REJECTED"))
                .andExpect(jsonPath("$.details.llm_raw_response").value("DROP TABLE orders"));
        assertCalls(2);
    }

    @Test
    void transportFailureOnFirstCallIsNotRetried() throws Exception {
        SERVER.enqueue(new MockResponse().setResponseCode(500));
        generate(mvc, "x").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("LLM_UNAVAILABLE"));
        assertCalls(1);
    }

    @Test
    void timeoutOnFirstCallIsNotRetried() throws Exception {
        SERVER.enqueue(new MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setBody("[]"));
        generate(mvc, "x").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(LlmFailureException.UNAVAILABLE_MESSAGE));
        assertCalls(1);
    }

    @Test
    void failureOnCorrectiveCallIsFinalAndNotRetried() throws Exception {
        reply("prose");
        SERVER.enqueue(new MockResponse().setResponseCode(429));
        generate(mvc, "x").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("LLM_QUOTA_EXCEEDED"));
        assertCalls(2);
    }

    @Test
    void neverMoreThanTwoCalls() throws Exception {
        reply("no sql 1");
        reply("no sql 2");
        reply("SELECT 1");
        generate(mvc, "x").andExpect(jsonPath("$.error").value("SQL_PARSE_ERROR"));
        assertCalls(2);
        generate(mvc, "consume leftover").andExpect(status().isOk());
        drain();
    }

    /** Deadline 1.5 s, hf.timeout 1 s: a 0.7 s first call leaves 0.8 s, so call 2 must not start (9 of 13 s, scaled). */
    @Nested
    @TestPropertySource(properties = "sql.generation.deadline=1500ms")
    class DeadlineGuard {

        @Autowired MockMvc nestedMvc;

        @Test
        void correctiveCallIsSkippedWhenTooLittleTimeRemains() throws Exception {
            SERVER.enqueue(text("I can't help with dropping tables").setHeadersDelay(700, TimeUnit.MILLISECONDS));
            SERVER.enqueue(text("SELECT 1"));
            long t0 = System.nanoTime();
            generate(nestedMvc, "drop orders").andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("LLM_REFUSED"))
                    .andExpect(jsonPath("$.details.llm_raw_response").value("I can't help with dropping tables"));
            assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1500);
            assertCalls(1);
            generate(nestedMvc, "consume leftover").andExpect(status().isOk());
            drain();
        }

        @Test
        void slowGarbledReplyFailsAsParseErrorWithinDeadline() throws Exception {
            SERVER.enqueue(text("garbled").setHeadersDelay(700, TimeUnit.MILLISECONDS));
            SERVER.enqueue(text("SELECT 1"));
            generate(nestedMvc, "x").andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("SQL_PARSE_ERROR"))
                    .andExpect(jsonPath("$.details.llm_raw_response").value("garbled"));
            assertCalls(1);
            generate(nestedMvc, "consume leftover").andExpect(status().isOk());
            drain();
        }

        @Test
        void correctiveCallRunsWhenTimeRemains() throws Exception {
            reply("prose");
            reply("SELECT 1");
            generate(nestedMvc, "x").andExpect(status().isOk());
            assertCalls(2);
        }
    }

    @Nested
    @TestPropertySource(properties = "sql.prompt.version=vtest")
    class RetryTemplateVersion {

        @Autowired MockMvc nestedMvc;

        @Test
        void usesTheVersionedRetryTemplate() throws Exception {
            reply("nope");
            reply("SELECT 1");
            generate(nestedMvc, "count orders").andExpect(status().isOk());
            SERVER.takeRequest(1, TimeUnit.SECONDS);
            assertThat(inputs(SERVER.takeRequest(1, TimeUnit.SECONDS)))
                    .isEqualTo("TEST-RETRY postgresql :: count orders :: nope\n");
        }
    }
}
