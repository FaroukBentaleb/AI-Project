package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Story 6.5: health polling never reaches the LLM (MockWebServer request count stays 0), root health excludes the
 * dependencies group, the {@code llm} indicator follows the last real call, and each generation logs one NFR7 line
 * without prompt text.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "sql.rag.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
class GenerationObservabilityIT extends AbstractIntegrationTest {

    static final MockWebServer LLM = start();

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
        LLM.shutdown();
    }

    @DynamicPropertySource
    static void llm(DynamicPropertyRegistry r) {
        r.add("hf.base-url", () -> LLM.url("/models").toString());
        r.add("hf.timeout", () -> "2s");
    }

    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.boot.actuate.health.HealthEndpointGroups groups;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode llmHealth() throws Exception {
        return mapper.readTree(send("GET", "/actuator/health/dependencies", null).body())
                .path("components").path("llm");
    }

    private void assertRootUpWithoutDependencies() throws Exception {
        HttpResponse<String> root = send("GET", "/actuator/health", null);
        assertThat(root.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(root.body());
        assertThat(body.path("status").asText()).isEqualTo("UP");
        // Root health hides components by default; membership is asserted on the group bean below.
        assertThat(root.body()).doesNotContain("llm").doesNotContain("qdrant");
        assertThat(groups.getPrimary().isMember("db")).isTrue();
        assertThat(groups.getPrimary().isMember("llm")).isFalse();
        assertThat(groups.getPrimary().isMember("qdrant")).isFalse();
        assertThat(groups.get("dependencies").isMember("llm")).isTrue();
    }

    @Test
    void healthNeverCallsTheLlmAndTracksTheLastCall(CapturedOutput output) throws Exception {
        String secretPrompt = "orders per region for customer acme-secret-42";

        for (int i = 0; i < 5; i++) {
            assertRootUpWithoutDependencies();
            assertThat(llmHealth().path("status").asText()).isEqualTo("UNKNOWN");
        }
        assertThat(LLM.getRequestCount()).isZero();
        HttpResponse<String> deps = send("GET", "/actuator/health/dependencies", null);
        assertThat(deps.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(deps.body()).path("components").has("qdrant")).isTrue();

        LLM.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(new Object[] {Map.of("generated_text", "SELECT 1")})));
        HttpResponse<String> ok = send("POST", "/api/sql/generate",
                mapper.writeValueAsString(Map.of("prompt", secretPrompt)));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(LLM.getRequestCount()).isEqualTo(1);
        assertThat(llmHealth().path("status").asText()).isEqualTo("UP");
        assertThat(LLM.getRequestCount()).isEqualTo(1);

        LLM.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        HttpResponse<String> failed = send("POST", "/api/sql/generate",
                mapper.writeValueAsString(Map.of("prompt", secretPrompt)));
        assertThat(failed.statusCode()).isEqualTo(503);
        JsonNode down = llmHealth();
        assertThat(down.path("status").asText()).isEqualTo("DOWN");
        assertThat(down.path("details").path("error").asText()).isEqualTo("LLM_UNAVAILABLE");
        assertRootUpWithoutDependencies();
        assertThat(LLM.getRequestCount()).isEqualTo(2);

        String logs = output.getOut();
        assertThat(logs).contains("generation outcome=OK prompt_version=v1 prompt_chars=" + secretPrompt.length()
                        + " examples=0 example_ids=[]")
                .contains("generation outcome=LLM_UNAVAILABLE")
                .contains("llm_calls=1")
                .doesNotContain("acme-secret-42")
                .doesNotContain("test-token");
    }
}
