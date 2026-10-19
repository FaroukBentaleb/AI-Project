package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.support.AbstractIntegrationTest;
import com.devassisthub.sql.support.StubEmbeddingServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Story 5.4: Reconciliation against the shared Qdrant with a stubbed embedding (no real HF calls). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "sql.reconcile.batch-size=2"})
class ReconcileIT extends AbstractIntegrationTest {

    @Autowired Reconciler reconciler;
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

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long create(String sql) throws Exception {
        HttpResponse<String> r = send("POST", "/api/sql/queries",
                "{\"title\":\"Reconcile\",\"description\":\"d\",\"sql_content\":\"" + sql + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return mapper.readTree(r.body()).path("id").asLong();
    }

    /** As if saved while Qdrant was down: no point, indexed = false. */
    private void unindex(long id) {
        qdrant.deletePoints(List.of(id));
        jdbc.update("UPDATE saved_query SET indexed = false WHERE id = ?", id);
    }

    private boolean indexed(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT indexed FROM saved_query WHERE id = ?",
                Boolean.class, id));
    }

    private boolean point(long id) {
        return qdrant.getPointPayload(id).isPresent();
    }

    @Test
    void indexesMissedQueriesRemovesOrphansAndFixesFlags() throws Exception {
        List<Long> missed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long id = create("SELECT " + (900 + i));
            unindex(id);
            missed.add(id);
        }
        // Orphan 1: row soft-deleted behind the service's back, point left over.
        long softDeleted = create("SELECT 910");
        jdbc.update("UPDATE saved_query SET deleted = true WHERE id = ?", softDeleted);
        // Orphan 2: point whose row no longer exists (e.g. create rolled back after the upsert).
        long gone = create("SELECT 911");
        jdbc.update("DELETE FROM saved_query WHERE id = ?", gone);
        // Point lost while the flag still says indexed.
        long pointLost = create("SELECT 912");
        qdrant.deletePoints(List.of(pointLost));
        // Stale flag on a non-qualifying row.
        long update = create("UPDATE users SET active = true");
        jdbc.update("UPDATE saved_query SET indexed = true WHERE id = ?", update);
        assertThat(point(softDeleted)).isTrue();
        assertThat(point(gone)).isTrue();

        Reconciler.Result result = reconciler.reconcile();

        assertThat(result.completed()).isTrue();
        for (long id : missed) {
            assertThat(indexed(id)).isTrue();
            assertThat(qdrant.getPointPayload(id).orElseThrow().path("title").asText()).isEqualTo("Reconcile");
        }
        assertThat(point(softDeleted)).isFalse();
        assertThat(point(gone)).isFalse();
        assertThat(point(pointLost)).isTrue();
        assertThat(indexed(pointLost)).isTrue();
        assertThat(indexed(update)).isFalse();
        assertThat(point(update)).isFalse();
        // Indexing is not an edit.
        assertThat(jdbc.queryForObject("SELECT version FROM saved_query WHERE id = ?", Integer.class, missed.get(0)))
                .isZero();

        // A second run has nothing left to do for these rows.
        Reconciler.Result again = reconciler.reconcile();
        assertThat(again.orphansRemoved()).isZero();
        assertThat(again.indexed()).isZero();
    }

    @Test
    void embeddingOutageLeavesRowsUnindexedForTheNextRun() throws Exception {
        long id = create("SELECT 920");
        unindex(id);
        StubEmbeddingServer.failWith(500);

        reconciler.reconcile();
        assertThat(indexed(id)).isFalse();
        assertThat(point(id)).isFalse();

        StubEmbeddingServer.reset();
        reconciler.reconcile();
        assertThat(indexed(id)).isTrue();
        assertThat(point(id)).isTrue();
    }

    @Test
    void reindexEndpointReturns202AndReconcilesAsynchronously() throws Exception {
        long id = create("SELECT 930");
        unindex(id);

        HttpResponse<String> r = send("POST", "/api/sql/admin/reindex", null);
        assertThat(r.statusCode()).isEqualTo(202);
        assertThat(mapper.readValue(r.body(), Map.class)).containsEntry("status", "accepted");

        long deadline = System.currentTimeMillis() + 15_000;
        while (!indexed(id) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(indexed(id)).isTrue();
        assertThat(point(id)).isTrue();
    }
}
