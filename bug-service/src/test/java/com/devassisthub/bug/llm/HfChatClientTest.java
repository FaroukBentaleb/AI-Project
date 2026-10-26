package com.devassisthub.bug.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devassisthub.bug.config.HfProperties;
import com.devassisthub.bug.exception.ApiException;
import com.devassisthub.bug.llm.HfChatClient.Message;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class HfChatClientTest {

    private static final List<Message> MESSAGES = List.of(new Message("user", "hello"));

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private HfChatClient client(String token, int attempts, Duration timeout) {
        return new HfChatClient(new HfProperties(token, "org/model", server.url("/v1").toString(), timeout,
                Duration.ofSeconds(1), attempts, Duration.ofMillis(1), 300));
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse reply(String content) {
        return json(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}");
    }

    private static void assertError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getError()).isEqualTo(code);
        });
    }

    @Test
    void postsAChatCompletionWithTheTokenAndReturnsTheContent() throws InterruptedException {
        server.enqueue(reply("{}"));

        String content = client("hf_secret", 1, Duration.ofSeconds(2)).complete(MESSAGES);

        assertThat(content).isEqualTo("{}");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer hf_secret");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"org/model\"", "\"role\":\"user\"", "\"content\":\"hello\"",
                "\"max_tokens\":300");
    }

    @Test
    void missingTokenFailsWithoutCallingTheProvider() {
        assertError(() -> client(" ", 3, Duration.ofSeconds(1)).complete(MESSAGES), HttpStatus.SERVICE_UNAVAILABLE,
                "LLM_NOT_CONFIGURED");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void rejectedTokenIsNotRetried() {
        server.enqueue(json(401, "{\"error\":\"Invalid credentials\"}"));

        assertError(() -> client("t", 3, Duration.ofSeconds(1)).complete(MESSAGES), HttpStatus.BAD_GATEWAY,
                "LLM_INVALID_TOKEN");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void exhaustedCreditsAndRateLimitsAreQuotaErrors() {
        server.enqueue(json(402, "{\"error\":\"You have no remaining credits.\"}"));
        assertError(() -> client("t", 3, Duration.ofSeconds(1)).complete(MESSAGES), HttpStatus.TOO_MANY_REQUESTS,
                "LLM_QUOTA_EXCEEDED");

        server.enqueue(json(429, "{}").setHeader("Retry-After", "7"));
        assertThatThrownBy(() -> client("t", 3, Duration.ofSeconds(1)).complete(MESSAGES))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(7));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void unsupportedModelIsReported() {
        server.enqueue(json(400, "{\"error\":{\"code\":\"model_not_supported\"}}"));

        assertError(() -> client("t", 3, Duration.ofSeconds(1)).complete(MESSAGES), HttpStatus.BAD_GATEWAY,
                "LLM_MODEL_UNAVAILABLE");
    }

    @Test
    void serverErrorsAreRetriedThenSucceed() {
        server.enqueue(json(503, "{}"));
        server.enqueue(json(500, "{}"));
        server.enqueue(reply("ok"));

        assertThat(client("t", 3, Duration.ofSeconds(1)).complete(MESSAGES)).isEqualTo("ok");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void persistentServerErrorsGiveUpAfterTheLastAttempt() {
        server.enqueue(json(502, "{}"));
        server.enqueue(json(502, "{}"));

        assertError(() -> client("t", 2, Duration.ofSeconds(1)).complete(MESSAGES), HttpStatus.BAD_GATEWAY,
                "LLM_UNAVAILABLE");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void slowAnswerIsATimeout() {
        // A non-streaming completion sends its headers only once generation is done, so a slow model delays them.
        server.enqueue(reply("late").setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS));

        assertError(() -> client("t", 1, Duration.ofMillis(300)).complete(MESSAGES), HttpStatus.GATEWAY_TIMEOUT,
                "LLM_TIMEOUT");
    }

    @Test
    void replyWithoutContentIsInvalidOutput() {
        server.enqueue(json(200, "{\"choices\":[]}"));

        assertError(() -> client("t", 1, Duration.ofSeconds(1)).complete(MESSAGES), HttpStatus.BAD_GATEWAY,
                "LLM_INVALID_OUTPUT");
    }
}
