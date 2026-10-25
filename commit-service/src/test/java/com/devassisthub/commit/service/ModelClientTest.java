package com.devassisthub.commit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devassisthub.commit.config.ModelProperties;
import com.devassisthub.commit.exception.ApiException;
import java.io.IOException;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ModelClientTest {

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

    private ModelClient client(int attempts, Duration readTimeout) {
        return new ModelClient(new ModelProperties(server.url("/").toString().replaceAll("/$", ""),
                Duration.ofSeconds(1), readTimeout, attempts, 4000, 1500));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void postsTheDiffAndReturnsTheTrimmedMessage() throws InterruptedException {
        server.enqueue(json("{\"message\": \"  feat: add index page \\n\"}"));

        String message = client(2, Duration.ofSeconds(2)).generate("diff --git a/x b/x");

        assertThat(message).isEqualTo("feat: add index page");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/generate");
        // No h2c upgrade: uvicorn answers it by dropping the body.
        assertThat(request.getHeader("Upgrade")).isNull();
        assertThat(request.getHeader("HTTP2-Settings")).isNull();
        assertThat(request.getBody().readUtf8()).isEqualTo("{\"diff\":\"diff --git a/x b/x\"}");
    }

    @Test
    void emptyMessageIsAnInvalidResponse() {
        server.enqueue(json("{\"message\": \"   \"}"));

        assertThatThrownBy(() -> client(1, Duration.ofSeconds(2)).generate("d"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getError()).isEqualTo("MODEL_INVALID_RESPONSE");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
    }

    @Test
    void missingMessageFieldIsAnInvalidResponse() {
        server.enqueue(json("{}"));

        assertThatThrownBy(() -> client(1, Duration.ofSeconds(2)).generate("d"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getError()).isEqualTo("MODEL_INVALID_RESPONSE"));
    }

    @Test
    void tooLongMessageIsAnInvalidResponse() {
        server.enqueue(json("{\"message\": \"" + "x".repeat(ModelClient.MAX_MESSAGE_CHARS + 1) + "\"}"));

        assertThatThrownBy(() -> client(1, Duration.ofSeconds(2)).generate("d"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getError()).isEqualTo("MODEL_INVALID_RESPONSE"));
    }

    @Test
    void retriesAServerErrorThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json("{\"message\": \"fix: retry works\"}"));

        assertThat(client(2, Duration.ofSeconds(2)).generate("d")).isEqualTo("fix: retry works");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void givesUpAfterTheConfiguredAttempts() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client(2, Duration.ofSeconds(2)).generate("d"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getError()).isEqualTo("MODEL_UNAVAILABLE"));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void doesNotRetryAClientError() {
        server.enqueue(new MockResponse().setResponseCode(422));
        server.enqueue(json("{\"message\": \"unused\"}"));

        assertThatThrownBy(() -> client(3, Duration.ofSeconds(2)).generate("d"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getError()).isEqualTo("MODEL_UNAVAILABLE"));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aSlowModelIsATimeout() {
        server.enqueue(json("{\"message\": \"too late\"}").setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> client(1, Duration.ofMillis(300)).generate("d"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getError()).isEqualTo("MODEL_TIMEOUT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                });
    }

    @Test
    void anUnreachableModelIsUnavailable() throws IOException {
        server.shutdown();

        assertThatThrownBy(() -> client(1, Duration.ofSeconds(1)).generate("d"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getError()).isEqualTo("MODEL_UNAVAILABLE"));
    }
}
