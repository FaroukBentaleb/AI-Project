package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.rag.CollectionBootstrap;
import com.devassisthub.sql.rag.EmbeddingClient;
import com.devassisthub.sql.rag.EmbeddingResult;
import com.devassisthub.sql.rag.ExampleRetriever;
import com.devassisthub.sql.rag.QdrantClient;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import com.devassisthub.sql.support.StubEmbeddingServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Story 6.4 end to end: real Qdrant (Testcontainer), stub embedding with pinned vectors, MockWebServer LLM.
 * Asserts the outgoing LLM prompt carries the retrieved Indexed Queries and {@code examples_used} matches.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class GenerateRagIT extends AbstractIntegrationTest {

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
        r.add("groq.base-url", () -> LLM.url("/models").toString());
        r.add("groq.timeout", () -> "2s");
    }

    @Autowired ObjectMapper mapper;
    @Autowired CollectionBootstrap bootstrap;
    @Autowired QdrantClient qdrant;
    @Autowired EmbeddingClient embedding;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private String pin;

    @BeforeEach
    void ready() throws InterruptedException {
        assertThat(bootstrap.tryBootstrap()).isTrue();
        StubEmbeddingServer.reset();
        pin = "ragpin" + UUID.randomUUID().toString().replace("-", "");
        while (LLM.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // drop leftovers
        }
    }

    @AfterEach
    void resetStub() {
        StubEmbeddingServer.reset();
    }

    private JsonNode send(String method, String path, String body, int expected) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(expected);
        return res.body().isEmpty() ? null : mapper.readTree(res.body());
    }

    private long save(String title, String sql) throws Exception {
        String body = mapper.writeValueAsString(Map.of("title", title, "description", "orders " + pin,
                "sql_content", sql, "tags", List.of("orders")));
        return send("POST", "/api/sql/queries", body, 201).path("id").asLong();
    }

    private static void reply(String text) throws IOException {
        LLM.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(new ObjectMapper().writeValueAsString(Map.of("choices", java.util.List.of(Map.of("message", Map.of("content", text)))))));
    }

    private JsonNode generate(String prompt) throws Exception {
        return send("POST", "/api/sql/generate", mapper.writeValueAsString(Map.of("prompt", prompt)), 200);
    }

    private String llmPrompt() throws Exception {
        RecordedRequest req = LLM.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        return mapper.readTree(req.getBody().readUtf8()).at("/messages/0/content").asText();
    }

    private static List<Long> ids(JsonNode used) {
        List<Long> out = new ArrayList<>();
        used.forEach(n -> out.add(n.path("id").asLong()));
        return out;
    }

    @Test
    void indexedQueriesOnlyAreInjectedAndListed() throws Exception {
        StubEmbeddingServer.pin(pin);
        long a = save("Weekly orders by region " + pin, "SELECT region, count(*) FROM orders GROUP BY region");
        long b = save("Orders per region last week " + pin, "SELECT region FROM orders LIMIT 10");
        long dep = save("Deprecated orders " + pin, "SELECT 1 FROM legacy_orders LIMIT 1");
        send("PUT", "/api/sql/queries/" + dep + "/deprecation", "{\"reason\":\"old\"}", 200);
        long del = save("Deleted orders " + pin, "SELECT 1 FROM gone_orders LIMIT 1");
        send("DELETE", "/api/sql/queries/" + del, null, 204);
        long rw = save("Writes orders " + pin, "DELETE FROM orders");
        // Simulate stale points: the DB re-check must still keep them out.
        float[] v = ((EmbeddingResult.Embedded) embedding.embed(pin)).vector();
        for (long stale : List.of(dep, del, rw)) {
            qdrant.upsertPoint(stale, v, Map.of("title", "stale"));
        }

        reply("SELECT region, count(*) FROM orders GROUP BY region");
        JsonNode res = generate("weekly orders by region " + pin);

        assertThat(ids(res.path("examples_used"))).containsExactlyInAnyOrder(a, b);
        assertThat(res.path("examples_used").findValuesAsText("title"))
                .contains("Weekly orders by region " + pin);
        assertThat(res.path("warnings").toString()).doesNotContain("no similar").doesNotContain("unavailable");
        String prompt = llmPrompt();
        assertThat(prompt).contains("<example id=\"" + a + "\">").contains("<example id=\"" + b + "\">")
                .contains("SELECT region, count(*) FROM orders GROUP BY region")
                .doesNotContain("legacy_orders").doesNotContain("gone_orders")
                .doesNotContain("<example id=\"" + rw + "\">");
        assertThat(prompt.split("<example id=", -1).length - 1).isLessThanOrEqualTo(5);
    }

    @Test
    void noPointAboveThresholdWarnsNoSimilar() throws Exception {
        reply("SELECT 1");
        JsonNode res = generate("something unrelated " + pin);
        assertThat(res.path("examples_used").isArray()).isTrue();
        assertThat(res.path("examples_used")).isEmpty();
        assertThat(res.path("warnings").toString()).contains(ExampleRetriever.NO_SIMILAR_WARNING);
        assertThat(llmPrompt()).doesNotContain("<example id=");
    }

    @Test
    void embeddingFailureFallsBackToZeroShotWithWarning() throws Exception {
        StubEmbeddingServer.failWith(500);
        reply("SELECT 1");
        JsonNode res = generate("weekly orders " + pin);
        assertThat(res.path("sql").asText()).startsWith("SELECT 1");
        assertThat(res.path("examples_used")).isEmpty();
        assertThat(res.path("warnings").toString()).contains(ExampleRetriever.RAG_UNAVAILABLE_WARNING);
    }
}
