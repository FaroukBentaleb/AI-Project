package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deprecate / restore (FR7) and the deprecated filter against real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class SavedQueryDeprecationIT extends AbstractIntegrationTest {


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
                + "\",\"description\":\"deprecation target\",\"sql_content\":\"SELECT 1\",\"tags\":[\"" + tag + "\"]}");
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    private Resp deprecate(long id, String body) throws Exception {
        return send("PUT", "/api/sql/queries/" + id + "/deprecation", body);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT deprecated, deprecated_reason, deprecated_at, replaced_by, version "
                + "FROM saved_query WHERE id = ?", id);
    }

    @Test
    void deprecateWithReplacementThenGetShowsFields() throws Exception {
        long old = create("Old report", "dep-a");
        long repl = create("New report", "dep-a");
        Resp r = deprecate(old, "{\"reason\":\"use the new one\",\"replaced_by\":" + repl + "}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("deprecated").asBoolean()).isTrue();
        assertThat(r.body().get("deprecated_reason").asText()).isEqualTo("use the new one");
        assertThat(r.body().get("deprecated_at").isNull()).isFalse();
        assertThat(r.body().get("replaced_by").asLong()).isEqualTo(repl);

        JsonNode g = send("GET", "/api/sql/queries/" + old, null).body();
        assertThat(g.get("deprecated").asBoolean()).isTrue();
        assertThat(g.get("replaced_by").asLong()).isEqualTo(repl);
        assertThat(row(old).get("replaced_by")).isEqualTo(repl);
    }

    @Test
    void deprecateWithoutReplacement() throws Exception {
        long id = create("No repl", "dep-b");
        Resp r = deprecate(id, "{\"reason\":\"obsolete\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("deprecated").asBoolean()).isTrue();
        assertThat(r.body().get("replaced_by").isNull()).isTrue();
    }

    @Test
    void redeprecateUpdatesReasonAndReplacementKeepsTimestamp() throws Exception {
        long id = create("Twice", "dep-c");
        long r1 = create("Repl one", "dep-c");
        long r2 = create("Repl two", "dep-c");
        assertThat(deprecate(id, "{\"reason\":\"first\",\"replaced_by\":" + r1 + "}").status()).isEqualTo(200);
        Object at = row(id).get("deprecated_at");
        Resp again = deprecate(id, "{\"reason\":\"second\",\"replaced_by\":" + r2 + "}");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("deprecated_reason").asText()).isEqualTo("second");
        assertThat(again.body().get("replaced_by").asLong()).isEqualTo(r2);
        assertThat(row(id).get("deprecated_at")).isEqualTo(at);
    }

    @Test
    void restoreClearsAndRepeatedRestoreChangesNothing() throws Exception {
        long id = create("Restore me", "dep-d");
        long repl = create("Restore repl", "dep-d");
        deprecate(id, "{\"reason\":\"r\",\"replaced_by\":" + repl + "}");
        Resp r = send("DELETE", "/api/sql/queries/" + id + "/deprecation", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("deprecated").asBoolean()).isFalse();
        Map<String, Object> after = row(id);
        assertThat(after.get("deprecated")).isEqualTo(false);
        assertThat(after.get("deprecated_reason")).isNull();
        assertThat(after.get("deprecated_at")).isNull();
        assertThat(after.get("replaced_by")).isNull();

        Resp again = send("DELETE", "/api/sql/queries/" + id + "/deprecation", null);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("deprecated").asBoolean()).isFalse();
        assertThat(row(id)).isEqualTo(after);
    }

    @Test
    void invalidReasonOrReplacementIs400AndRowUnchanged() throws Exception {
        long id = create("Bad input", "dep-e");
        long deleted = create("Deleted repl", "dep-e");
        assertThat(send("DELETE", "/api/sql/queries/" + deleted, null).status()).isEqualTo(204);
        Map<String, Object> before = row(id);

        List<String[]> cases = new ArrayList<>();
        cases.add(new String[] {"{}", "reason"});
        cases.add(new String[] {"{\"reason\":\"  \"}", "reason"});
        cases.add(new String[] {"{\"reason\":\"r\",\"replaced_by\":987654}", "replaced_by"});
        cases.add(new String[] {"{\"reason\":\"r\",\"replaced_by\":" + deleted + "}", "replaced_by"});
        cases.add(new String[] {"{\"reason\":\"r\",\"replaced_by\":" + id + "}", "replaced_by"});
        for (String[] c : cases) {
            Resp r = deprecate(id, c[0]);
            assertThat(r.status()).as(c[0]).isEqualTo(400);
            assertThat(r.body().get("error").asText()).as(c[0]).isEqualTo("VALIDATION_FAILED");
            assertThat(r.body().get("field").asText()).as(c[0]).isEqualTo(c[1]);
        }
        assertThat(deprecate(id, null).status()).isEqualTo(400);
        assertThat(deprecate(id, "not json").status()).isEqualTo(400);
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void unknownOrDeletedTargetIs404AndBadIdIs400() throws Exception {
        long gone = create("Gone", "dep-f");
        assertThat(send("DELETE", "/api/sql/queries/" + gone, null).status()).isEqualTo(204);
        for (long id : new long[] {987654L, gone}) {
            assertThat(deprecate(id, "{\"reason\":\"r\"}").status()).isEqualTo(404);
            Resp r = send("DELETE", "/api/sql/queries/" + id + "/deprecation", null);
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.body().get("error").asText()).isEqualTo("NOT_FOUND");
        }
        assertThat(row(gone).get("deprecated")).isEqualTo(false);
        Resp bad = send("PUT", "/api/sql/queries/abc/deprecation", "{\"reason\":\"r\"}");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("field").asText()).isEqualTo("id");
        Resp bad2 = send("DELETE", "/api/sql/queries/99999999999999999999/deprecation", null);
        assertThat(bad2.status()).isEqualTo(400);
        assertThat(bad2.body().get("field").asText()).isEqualTo("id");
    }

    private List<Long> ids(String path) throws Exception {
        List<Long> out = new ArrayList<>();
        for (JsonNode n : send("GET", path, null).body().get("content")) {
            out.add(n.get("id").asLong());
        }
        return out;
    }

    @Test
    void deprecatedStillListedAndFilterWorks() throws Exception {
        long live = create("Filter live", "dep-g");
        long dep = create("Filter dep", "dep-g");
        deprecate(dep, "{\"reason\":\"r\"}");

        assertThat(ids("/api/sql/queries?tags=dep-g")).containsExactlyInAnyOrder(live, dep);
        assertThat(ids("/api/sql/queries?tags=dep-g&deprecated=true")).containsExactly(dep);
        assertThat(ids("/api/sql/queries?tags=dep-g&deprecated=false")).containsExactly(live);
        assertThat(ids("/api/sql/queries?q=filter&tags=dep-g&deprecated=TRUE")).containsExactly(dep);
        assertThat(send("GET", "/api/sql/queries?tags=dep-g&deprecated=true", null).body()
                .get("total_elements").asLong()).isEqualTo(1);
        Resp bad = send("GET", "/api/sql/queries?deprecated=yes", null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("field").asText()).isEqualTo("deprecated");
    }

    @Test
    void v4MigrationAddsColumnsAndForeignKey() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).startsWith("1", "2", "3", "4");
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_name = 'saved_query' AND column_name = 'deprecated'", String.class)).isEqualTo("false");
        assertThat(jdbc.queryForList("SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_name = 'saved_query' AND constraint_type = 'FOREIGN KEY'", String.class))
                .contains("fk_saved_query_replaced_by");
    }
}
