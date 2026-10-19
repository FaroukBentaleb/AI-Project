package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.config.QdrantConfig;
import com.devassisthub.sql.rag.EmbeddingResult.Cause;
import com.devassisthub.sql.rag.EmbeddingResult.Embedded;
import com.devassisthub.sql.rag.EmbeddingResult.Unavailable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Story 5.2: every HF failure mode against a simulated provider; never the real API. */
@ExtendWith(OutputCaptureExtension.class)
class EmbeddingClientTest {

    private static final String TOKEN = "hf_test_secret_token_123";
    private static final String MODEL = "sentence-transformers/all-MiniLM-L6-v2";
    private static final int DIM = 384;

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

    private EmbeddingClient client(String token, String model) {
        EmbeddingProperties props = new EmbeddingProperties(token, model, DIM, Duration.ofSeconds(2),
                server.url("/hf-inference/models").toString());
        return new QdrantConfig().embeddingClient(props);
    }

    private EmbeddingClient client() {
        return client(TOKEN, MODEL);
    }

    private static String vector(int n) {
        return IntStream.range(0, n).mapToObj(i -> String.valueOf(i / 1000.0))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static Unavailable unavailable(EmbeddingResult r) {
        assertThat(r).isInstanceOf(Unavailable.class);
        return (Unavailable) r;
    }

    @Test
    void successReturnsVectorAndCallsFeatureExtraction() throws Exception {
        server.enqueue(json(200, vector(DIM)));

        EmbeddingResult r = client().embed("monthly revenue by region");

        assertThat(r).isInstanceOf(Embedded.class);
        float[] v = ((Embedded) r).vector();
        assertThat(v).hasSize(DIM);
        assertThat(v[1]).isEqualTo(0.001f);
        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath())
                .isEqualTo("/hf-inference/models/sentence-transformers/all-MiniLM-L6-v2/pipeline/feature-extraction");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(req.getBody().readUtf8()).isEqualTo("{\"inputs\":\"monthly revenue by region\"}");
    }

    @Test
    void nestedSingleRowIsAccepted() {
        server.enqueue(json(200, "[" + vector(DIM) + "]"));

        assertThat(client().embed("x")).isInstanceOf(Embedded.class);
    }

    @Test
    void dimensionMismatchIsAConfigurationError() {
        server.enqueue(json(200, vector(768)));

        Unavailable u = unavailable(client().embed("x"));

        assertThat(u.cause()).isEqualTo(Cause.DIMENSION_MISMATCH);
        assertThat(u.cause().errorCode()).isEqualTo("LLM_NOT_CONFIGURED");
        assertThat(u.detail()).contains("768").contains("384");
    }

    @Test
    void timeoutAfterTwoSecondsIsUnavailableWithinAboutTwoSeconds() {
        server.enqueue(json(200, vector(DIM)).setHeadersDelay(5, TimeUnit.SECONDS));

        long start = System.nanoTime();
        Unavailable u = unavailable(client().embed("x"));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(u.cause()).isEqualTo(Cause.TIMEOUT);
        assertThat(u.cause().errorCode()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(ms).isBetween(1500L, 3000L);
        assertThat(server.getRequestCount()).isEqualTo(1); // no retry
    }

    @Test
    void unauthorizedIsInvalidToken() {
        server.enqueue(json(401, "{\"error\":\"Invalid credentials in Authorization header\"}"));

        Unavailable u = unavailable(client().embed("x"));

        assertThat(u.cause()).isEqualTo(Cause.INVALID_TOKEN);
        assertThat(u.cause().errorCode()).isEqualTo("LLM_NOT_CONFIGURED");
    }

    @Test
    void forbiddenIsInvalidToken() {
        server.enqueue(json(403, "{\"error\":\"forbidden\"}"));

        assertThat(unavailable(client().embed("x")).cause()).isEqualTo(Cause.INVALID_TOKEN);
    }

    @Test
    void tooManyRequestsIsQuotaExceededWithoutRetry() {
        server.enqueue(json(429, "{\"error\":\"rate limit\"}"));

        Unavailable u = unavailable(client().embed("x"));

        assertThat(u.cause()).isEqualTo(Cause.QUOTA_EXCEEDED);
        assertThat(u.cause().errorCode()).isEqualTo("LLM_QUOTA_EXCEEDED");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void modelLoadingCarriesRetryAfterFromEstimatedTime() {
        server.enqueue(json(503, "{\"error\":\"Model is currently loading\",\"estimated_time\":20.3}"));

        Unavailable u = unavailable(client().embed("x"));

        assertThat(u.cause()).isEqualTo(Cause.MODEL_LOADING);
        assertThat(u.retryAfterSeconds()).isEqualTo(21);
        assertThat(u.cause().errorCode()).isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    void otherServerErrorIsProviderError() {
        server.enqueue(json(500, "boom"));

        Unavailable u = unavailable(client().embed("x"));

        assertThat(u.cause()).isEqualTo(Cause.PROVIDER_ERROR);
        assertThat(u.retryAfterSeconds()).isNull();
    }

    @Test
    void emptyBodyIsEmptyResponse() {
        server.enqueue(new MockResponse().setResponseCode(200));

        assertThat(unavailable(client().embed("x")).cause()).isEqualTo(Cause.EMPTY_RESPONSE);
    }

    @Test
    void nonVectorBodyIsMalformed() {
        server.enqueue(json(200, "{\"error\":\"unexpected\"}"));

        assertThat(unavailable(client().embed("x")).cause()).isEqualTo(Cause.MALFORMED_RESPONSE);
    }

    @Test
    void tokenLevelOutputIsMalformed() {
        server.enqueue(json(200, "[" + vector(DIM) + "," + vector(DIM) + "]"));

        assertThat(unavailable(client().embed("x")).cause()).isEqualTo(Cause.MALFORMED_RESPONSE);
    }

    @Test
    void noTokenConfiguredMakesNoCall() {
        Unavailable u = unavailable(client("", MODEL).embed("x"));

        assertThat(u.cause()).isEqualTo(Cause.NOT_CONFIGURED);
        assertThat(u.cause().errorCode()).isEqualTo("LLM_NOT_CONFIGURED");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void noModelConfiguredMakesNoCall() {
        assertThat(unavailable(client(TOKEN, null).embed("x")).cause()).isEqualTo(Cause.NOT_CONFIGURED);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void blankTextMakesNoCall() {
        assertThat(unavailable(client().embed("  ")).cause()).isEqualTo(Cause.EMPTY_INPUT);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void unreachableProviderIsUnavailable() throws IOException {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        assertThat(unavailable(client().embed("x")).cause()).isIn(Cause.UNREACHABLE, Cause.TIMEOUT);
    }

    @Test
    void tokenIsNeverLogged(CapturedOutput output) {
        server.enqueue(json(401, "{\"error\":\"bad token\"}"));
        server.enqueue(json(200, vector(5)));
        EmbeddingClient c = client();

        c.embed("x");
        c.embed("x");

        assertThat(output.getAll()).doesNotContain(TOKEN);
        assertThat(new EmbeddingProperties(TOKEN, MODEL, DIM, Duration.ofSeconds(2), "u").toString())
                .doesNotContain(TOKEN);
    }
}
