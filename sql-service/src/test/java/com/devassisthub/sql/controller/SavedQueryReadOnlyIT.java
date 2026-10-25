package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.service.ReadOnlyBackfill;
import com.devassisthub.sql.support.AbstractIntegrationTest;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Story 4.4 I/O matrix against real PostgreSQL: flag on create/edit, list filter, V6 and startup backfill. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class SavedQueryReadOnlyIT extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReadOnlyBackfill backfill;
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

    private JsonNode create(String title, String sql) throws Exception {
        Resp r = send("POST", "/api/sql/queries", mapper.createObjectNode().put("title", title)
                .put("description", "d").put("sql_content", sql).toString());
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void updateSavesWith201AndNotReadOnly() throws Exception {
        JsonNode c = create("u", "UPDATE orders SET status='x'");
        assertThat(c.get("read_only").asBoolean()).isFalse();
        assertThat(texts(c.get("read_only_reasons"))).singleElement().asString().startsWith("NON_SELECT_STATEMENT");
        JsonNode g = send("GET", "/api/sql/queries/" + c.get("id").asLong(), null).body();
        assertThat(g.get("read_only").asBoolean()).isFalse();
        assertThat(g.get("read_only_reasons")).isEqualTo(c.get("read_only_reasons"));
    }

    @Test
    void selectIsReadOnlyAndNormalizedIsStored() throws Exception {
        JsonNode c = create("s", "```sql\nＳＥＬＥＣＴ 1\n```");
        assertThat(c.get("read_only").asBoolean()).isTrue();
        assertThat(c.get("read_only_reasons").size()).isZero();
        assertThat(c.get("sql_content").asText()).isEqualTo("SELECT 1");
        assertThat(jdbc.queryForObject("SELECT sql_content FROM saved_query WHERE id = ?", String.class,
                c.get("id").asLong())).isEqualTo("SELECT 1");
    }

    @Test
    void validatorWarningsReachResponseOnCreateOnly() throws Exception {
        JsonNode c = create("w", "SELECT pg_sleep(60)");
        assertThat(c.get("read_only").asBoolean()).isTrue();
        assertThat(c.get("warnings").size()).isEqualTo(1);
        assertThat(send("GET", "/api/sql/queries/" + c.get("id").asLong(), null).body().get("warnings").size())
                .isZero();
    }

    @Test
    void patchSqlToDeleteFlipsFlagAndTagsOnlyDoesNotRevalidate() throws Exception {
        long id = create("p", "SELECT 1").get("id").asLong();
        JsonNode p = send("PATCH", "/api/sql/queries/" + id,
                "{\"sql_content\":\"DELETE FROM orders\",\"version\":0}").body();
        assertThat(p.get("read_only").asBoolean()).isFalse();
        assertThat(texts(p.get("read_only_reasons"))).singleElement().asString().startsWith("NON_SELECT_STATEMENT");

        // Tamper the stored reasons: a tags-only PATCH must keep them (no re-validation).
        jdbc.update("UPDATE saved_query SET read_only_reasons = '{sentinel}' WHERE id = ?", id);
        JsonNode t = send("PATCH", "/api/sql/queries/" + id, "{\"tags\":[\"x\"],\"version\":1}").body();
        assertThat(texts(t.get("read_only_reasons"))).containsExactly("sentinel");
        assertThat(t.get("read_only").asBoolean()).isFalse();
    }

    @Test
    void putRevalidates() throws Exception {
        long id = create("r", "DELETE FROM orders").get("id").asLong();
        JsonNode p = send("PUT", "/api/sql/queries/" + id,
                "{\"title\":\"r\",\"description\":\"d\",\"sql_content\":\"SELECT 2\",\"version\":0}").body();
        assertThat(p.get("read_only").asBoolean()).isTrue();
        assertThat(p.get("read_only_reasons").size()).isZero();
    }

    @Test
    void readOnlyFilterCombinesWithQ() throws Exception {
        String tag = "ro" + UUID.randomUUID().toString().substring(0, 8);
        long ro = create(tag + " a", "SELECT 1").get("id").asLong();
        long rw = create(tag + " b", "DROP TABLE x").get("id").asLong();
        JsonNode t = send("GET", "/api/sql/queries?q=" + tag + "&read_only=true", null).body();
        JsonNode f = send("GET", "/api/sql/queries?q=" + tag + "&read_only=FALSE", null).body();
        JsonNode all = send("GET", "/api/sql/queries?q=" + tag, null).body();
        assertThat(t.get("content").findValuesAsText("id")).containsExactly(String.valueOf(ro));
        assertThat(f.get("content").findValuesAsText("id")).containsExactly(String.valueOf(rw));
        assertThat(all.get("content").size()).isEqualTo(2);
        Resp bad = send("GET", "/api/sql/queries?read_only=yes", null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("field").asText()).isEqualTo("read_only");
    }

    @Test
    void backfillSetsNullRowsWithoutTouchingSqlVersionOrUpdatedAt() throws Exception {
        long sel = create("b1", "SELECT 1").get("id").asLong();
        long upd = create("b2", "UPDATE t SET a = 1").get("id").asLong();
        // Simulate rows saved before V6, including a fenced text the backfill must not rewrite.
        jdbc.update("UPDATE saved_query SET read_only = NULL, read_only_reasons = '{}', sql_content = ? WHERE id = ?",
                "```sql\nSELECT 1\n```", sel);
        jdbc.update("UPDATE saved_query SET read_only = NULL, read_only_reasons = '{}' WHERE id = ?", upd);
        Map<String, Object> before = jdbc.queryForMap("SELECT version, updated_at FROM saved_query WHERE id = ?", sel);

        assertThat(backfill.backfill()).isGreaterThanOrEqualTo(2);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM saved_query WHERE read_only IS NULL", Long.class))
                .isZero();
        Map<String, Object> s = jdbc.queryForMap(
                "SELECT read_only, sql_content, version, updated_at FROM saved_query WHERE id = ?", sel);
        assertThat(s.get("read_only")).isEqualTo(true);
        assertThat(s.get("sql_content")).isEqualTo("```sql\nSELECT 1\n```");
        assertThat(s.get("version")).isEqualTo(before.get("version"));
        assertThat(s.get("updated_at")).isEqualTo(before.get("updated_at"));
        JsonNode u = send("GET", "/api/sql/queries/" + upd, null).body();
        assertThat(u.get("read_only").asBoolean()).isFalse();
        assertThat(texts(u.get("read_only_reasons"))).singleElement().asString().startsWith("NON_SELECT_STATEMENT");
        assertThat(backfill.backfill()).isZero();
    }

    @Test
    void v6MigrationApplied() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).startsWith("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'saved_query' AND column_name = 'read_only'", String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'saved_query' AND column_name = 'read_only_reasons'", String.class))
                .isEqualTo("ARRAY");
    }
}
