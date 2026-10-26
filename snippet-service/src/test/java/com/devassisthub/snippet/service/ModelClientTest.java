package com.devassisthub.snippet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devassisthub.snippet.config.ModelProperties;
import com.devassisthub.snippet.exception.ApiException;
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
                Duration.ofSeconds(1), readTimeout, attempts, 2500));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void postsCodeAndLanguageAndReturnsTheTrimmedDocstring() throws InterruptedException {
        server.enqueue(json("{\"docstring\": \"  Add two numbers.\\n\\nArgs:\\n    a: first \\n\"}"));

        String docstring = client(2, Duration.ofSeconds(15)).generate("def add(a, b): return a + b", "python");

        assertThat(docstring).isEqualTo("Add two numbers.\n\nArgs:\n    a: first");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/generate");
        // No h2c upgrade: uvicorn answers it by dropping the body.
        assertThat(request.getHeader("Upgrade")).isNull();
        assertThat(request.getHeader("HTTP2-Settings")).isNull();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"code\":\"def add(a, b): return a + b\"").contains("\"language\":\"python\"");
    }

    @Test
    void emptyDocstringIsAnInvalidResponse() {
        server.enqueue(json("{\"docstring\": \"   \"}"));

        assertThatThrownBy(() -> client(1, Duration.ofSeconds(15)).generate("c", "java"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getError()).isEqualTo("MODEL_INVALID_RESPONSE");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
    }

    @Test
    void unreadableReplyIsAnInvalidResponse() {
        server.enqueue(json("not json"));

        assertThatThrownBy(() -> client(1, Duration.ofSeconds(15)).generate("c", "java"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getError()).isEqualTo("MODEL_INVALID_RESPONSE"));
    }

    @Test
    void retriesAfterA5xxThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json("{\"docstring\": \"Does it.\"}"));

        assertThat(client(2, Duration.ofSeconds(15)).generate("c", "javascript")).isEqualTo("Does it.");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void doesNotRetryA4xx() {
        server.enqueue(new MockResponse().setResponseCode(422));

        assertThatThrownBy(() -> client(3, Duration.ofSeconds(15)).generate("c", "java"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getError()).isEqualTo("MODEL_UNAVAILABLE"));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void slowModelIsATimeout() {
        server.enqueue(json("{\"docstring\": \"late\"}").setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> client(1, Duration.ofMillis(300)).generate("c", "java"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getError()).isEqualTo("MODEL_TIMEOUT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                });
    }

    @Test
    void unreachableModelIsUnavailable() throws IOException {
        String url = server.url("/").toString().replaceAll("/$", "");
        server.shutdown();

        ModelClient client = new ModelClient(new ModelProperties(url, Duration.ofMillis(500),
                Duration.ofSeconds(1), 1, 2500));
        assertThatThrownBy(() -> client.generate("c", "java")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getError()).isEqualTo("MODEL_UNAVAILABLE"));
    }
}
