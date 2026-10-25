package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Usage counter (FR8) against real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class SavedQueryUsageIT extends AbstractIntegrationTest {


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
                + "\",\"description\":\"usage target\",\"sql_content\":\"SELECT 1\",\"tags\":[\"" + tag + "\"]}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("use_count").asLong()).isZero();
        return r.body().get("id").asLong();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT use_count, last_used_at, version, updated_at FROM saved_query WHERE id = ?", id);
    }

    private int use(long id) throws Exception {
        return send("POST", "/api/sql/queries/" + id + "/usage", null).status();
    }

    @Test
    void newQueryStartsAtZeroAndTwoPostsGiveTwo() throws Exception {
        long id = create("Usage twice", "use-a");
        JsonNode fresh = send("GET", "/api/sql/queries/" + id, null).body();
        assertThat(fresh.get("use_count").asLong()).isZero();
        assertThat(fresh.get("last_used_at").isNull()).isTrue();
        Map<String, Object> before = row(id);

        Resp first = send("POST", "/api/sql/queries/" + id + "/usage", null);
        assertThat(first.status()).isEqualTo(204);
        assertThat(first.body()).isNull();
        Timestamp firstAt = (Timestamp) row(id).get("last_used_at");
        assertThat(firstAt).isNotNull();
        Thread.sleep(5);
        assertThat(use(id)).isEqualTo(204);

        Map<String, Object> after = row(id);
        assertThat(after.get("use_count")).isEqualTo(2L);
        assertThat(((Timestamp) after.get("last_used_at")).after(firstAt)).isTrue();
        // usage is not an edit: optimistic version and updated_at untouched
        assertThat(after.get("version")).isEqualTo(before.get("version"));
        assertThat(after.get("updated_at")).isEqualTo(before.get("updated_at"));

        JsonNode view = send("GET", "/api/sql/queries/" + id, null).body();
        assertThat(view.get("use_count").asLong()).isEqualTo(2);
        assertThat(OffsetDateTime.parse(view.get("last_used_at").asText()).toInstant())
                .isEqualTo(((Timestamp) after.get("last_used_at")).toInstant());
    }

    @Test
    void concurrentPostsLoseNoIncrements() throws Exception {
        long id = create("Usage concurrent", "use-b");
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                calls.add(() -> use(id));
            }
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo(204);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(row(id).get("use_count")).isEqualTo((long) n);
    }

    @Test
    void unknownOrDeletedIs404AndBadIdIs400() throws Exception {
        Resp unknown = send("POST", "/api/sql/queries/987654/usage", null);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body().get("error").asText()).isEqualTo("NOT_FOUND");

        long id = create("Usage deleted", "use-c");
        assertThat(send("DELETE", "/api/sql/queries/" + id, null).status()).isEqualTo(204);
        Resp deleted = send("POST", "/api/sql/queries/" + id + "/usage", null);
        assertThat(deleted.status()).isEqualTo(404);
        assertThat(deleted.body().get("error").asText()).isEqualTo("NOT_FOUND");
        assertThat(row(id).get("use_count")).isEqualTo(0L);

        for (String bad : new String[] {"abc", "99999999999999999999"}) {
            Resp r = send("POST", "/api/sql/queries/" + bad + "/usage", null);
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().get("field").asText()).isEqualTo("id");
        }
    }

    @Test
    void deprecatedQueryCanStillBeUsed() throws Exception {
        long id = create("Usage deprecated", "use-e");
        assertThat(send("PUT", "/api/sql/queries/" + id + "/deprecation", "{\"reason\":\"old\"}").status())
                .isEqualTo(200);
        assertThat(use(id)).isEqualTo(204);
        assertThat(row(id).get("use_count")).isEqualTo(1L);
    }

    @Test
    void sortByUseCountAndLastUsedAt() throws Exception {
        long never = create("Sort never", "use-d");
        long once = create("Sort once", "use-d");
        long thrice = create("Sort thrice", "use-d");
        for (int i = 0; i < 3; i++) {
            assertThat(use(thrice)).isEqualTo(204);
        }
        Thread.sleep(5);
        assertThat(use(once)).isEqualTo(204);

        assertThat(ids("/api/sql/queries?tags=use-d&sort=use_count,desc")).containsExactly(thrice, once, never);
        assertThat(ids("/api/sql/queries?tags=use-d&sort=use_count,asc")).containsExactly(never, once, thrice);
        assertThat(ids("/api/sql/queries?tags=use-d&sort=last_used_at,desc")).containsExactly(once, thrice, never);
        assertThat(ids("/api/sql/queries?tags=use-d&sort=last_used_at,asc")).containsExactly(thrice, once, never);
    }

    private List<Long> ids(String path) throws Exception {
        Resp r = send("GET", path, null);
        assertThat(r.status()).as(path).isEqualTo(200);
        List<Long> out = new ArrayList<>();
        r.body().get("content").forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    @Test
    void v5MigrationAddsColumns() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).startsWith("1", "2", "3", "4", "5");
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_name = 'saved_query' AND column_name = 'use_count'", String.class)).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'saved_query' AND column_name = 'last_used_at'", String.class)).isEqualTo("YES");
    }
}
