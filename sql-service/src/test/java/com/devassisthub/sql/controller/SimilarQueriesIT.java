package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.rag.CollectionBootstrap;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import com.devassisthub.sql.support.StubEmbeddingServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Story 8.4 end to end: real Qdrant (Testcontainer) and the stub embedding server with pinned vectors. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class SimilarQueriesIT extends AbstractIntegrationTest {

    @Autowired ObjectMapper mapper;
    @Autowired CollectionBootstrap bootstrap;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private String pin;

    @BeforeEach
    void ready() {
        assertThat(bootstrap.tryBootstrap()).isTrue();
        StubEmbeddingServer.reset();
        pin = "simpin" + UUID.randomUUID().toString().replace("-", "");
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
                "sql_content", sql));
        return send("POST", "/api/sql/queries", body, 201).path("id").asLong();
    }

    private JsonNode similar() throws Exception {
        String body = mapper.writeValueAsString(Map.of("title", "Orders by region " + pin,
                "description", "draft", "sql_content", "SELECT region FROM orders"));
        return send("POST", "/api/sql/queries/similar", body, 200);
    }

    private static List<Long> ids(JsonNode arr) {
        List<Long> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.path("id").asLong()));
        return out;
    }

    @Test
    void upToThreeIndexedQueriesAndNeverDeprecated() throws Exception {
        StubEmbeddingServer.pin(pin);
        List<Long> live = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            live.add(save("Orders " + i + " " + pin, "SELECT region FROM orders LIMIT " + (i + 1)));
        }
        long dep = save("Deprecated " + pin, "SELECT 1 FROM legacy_orders LIMIT 1");
        send("PUT", "/api/sql/queries/" + dep + "/deprecation", "{\"reason\":\"old\"}", 200);

        JsonNode res = similar();
        assertThat(res.isArray()).isTrue();
        assertThat(res).hasSize(3);
        assertThat(ids(res)).doesNotContain(dep).allMatch(live::contains);
        assertThat(res.get(0).path("title").asText()).contains(pin);
    }

    @Test
    void nothingAboveThresholdIsEmpty() throws Exception {
        assertThat(similar()).isEmpty();
    }

    @Test
    void embeddingDownIsEmptyAndSaveStillWorks() throws Exception {
        StubEmbeddingServer.failWith(500);
        assertThat(similar()).isEmpty();
        assertThat(save("Still saves " + pin, "SELECT 1")).isPositive();
    }
}
