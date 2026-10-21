package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.support.AbstractIntegrationTest;
import com.devassisthub.sql.support.StubEmbeddingServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Story 5.3: synchronous index/remove on every mutation, against the shared Qdrant and a stubbed embedding. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class IndexSyncIT extends AbstractIntegrationTest {

    @Autowired QdrantClient qdrant;
    @Autowired CollectionBootstrap bootstrap;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void ready() {
        assertThat(bootstrap.tryBootstrap()).isTrue();
        StubEmbeddingServer.reset();
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

    private JsonNode create(String sql) throws Exception {
        return send("POST", "/api/sql/queries", "{\"title\":\"Active users\",\"description\":\"users seen today\","
                + "\"sql_content\":\"" + sql + "\",\"tags\":[\"users\"],\"original_nl_prompt\":\"who is active\"}", 201);
    }

    private boolean pointExists(long id) {
        return qdrant.getPointPayload(id).isPresent();
    }

    private boolean dbIndexed(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT indexed FROM saved_query WHERE id = ?",
                Boolean.class, id));
    }

    @Test
    void readOnlySaveIndexesOnePointWithPayload() throws Exception {
        JsonNode q = create("SELECT id FROM users");
        long id = q.path("id").asLong();
        assertThat(q.path("indexed").asBoolean()).isTrue();
        assertThat(q.path("warnings")).isEmpty();
        assertThat(q.path("version").asInt()).isZero();
        assertThat(dbIndexed(id)).isTrue();
        JsonNode payload = qdrant.getPointPayload(id).orElseThrow();
        assertThat(payload.path("title").asText()).isEqualTo("Active users");
        assertThat(payload.path("description").asText()).isEqualTo("users seen today");
        assertThat(payload.path("sql_content").asText()).isEqualTo("SELECT id FROM users");
        assertThat(payload.path("original_nl_prompt").asText()).isEqualTo("who is active");
        assertThat(payload.path("tags").get(0).asText()).isEqualTo("users");
        assertThat(send("GET", "/api/sql/queries/" + id, null, 200).path("indexed").asBoolean()).isTrue();
    }

    @Test
    void updateIsNotIndexed() throws Exception {
        JsonNode q = create("UPDATE users SET active = true");
        assertThat(q.path("indexed").asBoolean()).isFalse();
        assertThat(q.path("warnings")).isEmpty();
        assertThat(pointExists(q.path("id").asLong())).isFalse();
    }

    @Test
    void deprecateRemovesAndRestoreReindexes() throws Exception {
        long id = create("SELECT 1").path("id").asLong();
        JsonNode dep = send("PUT", "/api/sql/queries/" + id + "/deprecation", "{\"reason\":\"old\"}", 200);
        assertThat(dep.path("indexed").asBoolean()).isFalse();
        assertThat(pointExists(id)).isFalse();

        // Editing a deprecated query's description does not re-index it.
        int version = dep.path("version").asInt();
        JsonNode edited = send("PATCH", "/api/sql/queries/" + id,
                "{\"description\":\"new text\",\"version\":" + version + "}", 200);
        assertThat(edited.path("indexed").asBoolean()).isFalse();
        assertThat(pointExists(id)).isFalse();

        JsonNode restored = send("DELETE", "/api/sql/queries/" + id + "/deprecation", null, 200);
        assertThat(restored.path("indexed").asBoolean()).isTrue();
        assertThat(qdrant.getPointPayload(id).orElseThrow().path("description").asText()).isEqualTo("new text");
    }

    @Test
    void deleteRemovesPoint() throws Exception {
        long id = create("SELECT 2").path("id").asLong();
        assertThat(pointExists(id)).isTrue();
        send("DELETE", "/api/sql/queries/" + id, null, 204);
        assertThat(pointExists(id)).isFalse();
        assertThat(dbIndexed(id)).isFalse();
    }

    @Test
    void editIntoDeleteRemovesAndEditBackReindexes() throws Exception {
        JsonNode q = create("SELECT 3");
        long id = q.path("id").asLong();
        JsonNode edited = send("PATCH", "/api/sql/queries/" + id,
                "{\"sql_content\":\"DELETE FROM users\",\"version\":" + q.path("version").asInt() + "}", 200);
        assertThat(edited.path("indexed").asBoolean()).isFalse();
        assertThat(pointExists(id)).isFalse();

        JsonNode back = send("PUT", "/api/sql/queries/" + id, "{\"title\":\"T2\",\"description\":\"d\","
                + "\"sql_content\":\"SELECT 4\",\"version\":" + edited.path("version").asInt() + "}", 200);
        assertThat(back.path("indexed").asBoolean()).isTrue();
        assertThat(qdrant.getPointPayload(id).orElseThrow().path("title").asText()).isEqualTo("T2");
    }

    @Test
    void embeddingFailureStillSavesWithWarning() throws Exception {
        StubEmbeddingServer.failWith(500);
        JsonNode q = create("SELECT 5");
        long id = q.path("id").asLong();
        assertThat(q.path("indexed").asBoolean()).isFalse();
        assertThat(q.path("warnings").get(0).asText()).startsWith(QueryIndexer.WARNING_PREFIX);
        assertThat(pointExists(id)).isFalse();
        assertThat(send("GET", "/api/sql/queries/" + id, null, 200).path("title").asText()).isEqualTo("Active users");
    }

    /** NFR2: create and edit p95 < 3 s with 1 s embedding latency. */
    @Test
    void createAndEditMeetLatencyBudgetWithSlowEmbedding() throws Exception {
        StubEmbeddingServer.delay(1000);
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            JsonNode q = create("SELECT " + (100 + i));
            millis.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(q.path("indexed").asBoolean()).isTrue();
            t0 = System.nanoTime();
            send("PATCH", "/api/sql/queries/" + q.path("id").asLong(),
                    "{\"title\":\"renamed " + i + "\",\"version\":" + q.path("version").asInt() + "}", 200);
            millis.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(millis);
        assertThat(millis.get((int) Math.ceil(millis.size() * 0.95) - 1)).isLessThan(3000L);
    }
}
