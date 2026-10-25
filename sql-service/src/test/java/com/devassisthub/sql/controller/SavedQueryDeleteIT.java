package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Soft delete (FR6) against real PostgreSQL, including the native-SQL library search. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class SavedQueryDeleteIT extends AbstractIntegrationTest {


    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    private record Resp(int status, JsonNode body) { }

    private Resp send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body() == null || r.body().isEmpty() ? null : mapper.readTree(r.body()));
    }

    private long create(String title, String tag) throws Exception {
        Resp r = send("POST", "/api/sql/queries", "{\"title\":\"" + title
                + "\",\"description\":\"soft delete target\",\"sql_content\":\"SELECT 1\",\"tags\":[\"" + tag + "\"]}");
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    @Test
    void deleteReturns204KeepsRowAndHidesItFromGet() throws Exception {
        long id = create("Delete me", "del-a");
        Resp d = send("DELETE", "/api/sql/queries/" + id, null);
        assertThat(d.status()).isEqualTo(204);
        assertThat(d.body()).isNull();

        Map<String, Object> row = jdbc.queryForMap("SELECT deleted, deleted_at FROM saved_query WHERE id = ?", id);
        assertThat(row.get("deleted")).isEqualTo(true);
        assertThat(row.get("deleted_at")).isNotNull();

        Resp g = send("GET", "/api/sql/queries/" + id, null);
        assertThat(g.status()).isEqualTo(404);
        assertThat(g.body().get("error").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void deletedRowIsAbsentFromBrowseAndSearchAndCounts() throws Exception {
        long keep = create("Zebra keep", "del-b");
        long gone = create("Zebra gone", "del-b");
        assertThat(send("DELETE", "/api/sql/queries/" + gone, null).status()).isEqualTo(204);

        for (String path : new String[] {"/api/sql/queries?size=100", "/api/sql/queries?q=zebra",
                "/api/sql/queries?tags=del-b", "/api/sql/queries?q=zebra&tags=del-b&author=anonymous"}) {
            JsonNode page = send("GET", path, null).body();
            boolean hasGone = false;
            boolean hasKeep = false;
            for (JsonNode n : page.get("content")) {
                hasGone |= n.get("id").asLong() == gone;
                hasKeep |= n.get("id").asLong() == keep;
            }
            assertThat(hasGone).as(path).isFalse();
            assertThat(hasKeep).as(path).isTrue();
            if (!path.contains("size=100")) {
                assertThat(page.get("total_elements").asLong()).as(path).isEqualTo(1);
            }
        }
    }

    @Test
    void patchPutAndDeleteOnDeletedRowAre404AndRowStaysDeleted() throws Exception {
        long id = create("Edit after delete", "del-c");
        assertThat(send("DELETE", "/api/sql/queries/" + id, null).status()).isEqualTo(204);
        Object deletedAt = jdbc.queryForObject("SELECT deleted_at FROM saved_query WHERE id = ?", Object.class, id);

        assertThat(send("PATCH", "/api/sql/queries/" + id, "{\"title\":\"x\",\"version\":1}").status())
                .isEqualTo(404);
        assertThat(send("PUT", "/api/sql/queries/" + id,
                "{\"title\":\"x\",\"description\":\"d\",\"sql_content\":\"SELECT 2\",\"version\":1}").status())
                .isEqualTo(404);
        Resp again = send("DELETE", "/api/sql/queries/" + id, null);
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.body().get("error").asText()).isEqualTo("NOT_FOUND");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, deleted, deleted_at FROM saved_query WHERE id = ?", id);
        assertThat(row.get("title")).isEqualTo("Edit after delete");
        assertThat(row.get("deleted")).isEqualTo(true);
        assertThat(row.get("deleted_at")).isEqualTo(deletedAt);
    }

    @Test
    void v3MigrationAddsColumnsAndIndex() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).startsWith("1", "2", "3");
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_name = 'saved_query' AND column_name = 'deleted'", String.class)).isEqualTo("false");
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'saved_query'", String.class))
                .contains("idx_saved_query_deleted");
    }

    @Test
    void unknownIdIs404AndBadIdIs400() throws Exception {
        assertThat(send("DELETE", "/api/sql/queries/987654", null).status()).isEqualTo(404);
        Resp bad = send("DELETE", "/api/sql/queries/abc", null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("field").asText()).isEqualTo("id");
    }
}
