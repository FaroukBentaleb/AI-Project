package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** NFR2: view, browse and search p95 < 300 ms on a seed-sized dataset, against real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class SavedQueryReadPerformanceIT extends AbstractIntegrationTest {

    static final int SEED_ROWS = 500;
    static final int WARMUP = 10;
    static final int SAMPLES = 50;


    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private long sampleId;

    @BeforeEach
    void seed() {
        if (jdbc.queryForObject("SELECT count(*) FROM saved_query", Long.class) < SEED_ROWS) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < SEED_ROWS; i++) {
                rows.add(new Object[] {"Seed query " + i + (i % 3 == 0 ? " orders" : " customers"),
                        "Seed description " + i, "SELECT " + i, "{tag" + (i % 10) + ",seed}",
                        i % 2 == 0 ? "alice" : "bob", i % 4 == 0, (long) (i % 17), i % 9 == 0});
            }
            jdbc.batchUpdate("INSERT INTO saved_query (title, description, sql_content, tags, author, "
                    + "is_llm_generated, use_count, deprecated, created_at, updated_at) "
                    + "VALUES (?, ?, ?, cast(? AS text[]), ?, ?, ?, ?, now(), now())", rows);
        }
        sampleId = jdbc.queryForObject("SELECT max(id) FROM saved_query", Long.class);
    }

    private int get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private long p95Millis(String path) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            assertThat(get(path)).as(path).isEqualTo(200);
        }
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            long t0 = System.nanoTime();
            int status = get(path);
            times.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(status).as(path).isEqualTo(200);
        }
        times.sort(null);
        return times.get((int) Math.ceil(SAMPLES * 0.95) - 1);
    }

    @Test
    void viewP95Under300ms() throws Exception {
        assertThat(p95Millis("/api/sql/queries/" + sampleId)).isLessThan(300);
    }

    @Test
    void browseP95Under300ms() throws Exception {
        assertThat(p95Millis("/api/sql/queries?size=20")).isLessThan(300);
        assertThat(p95Millis("/api/sql/queries?size=100&sort=use_count,desc")).isLessThan(300);
    }

    @Test
    void searchP95Under300ms() throws Exception {
        assertThat(p95Millis("/api/sql/queries?q=orders&tags=tag3,tag6&author=alice&llm_generated=false"
                + "&deprecated=false&sort=last_used_at,desc")).isLessThan(300);
    }
}
