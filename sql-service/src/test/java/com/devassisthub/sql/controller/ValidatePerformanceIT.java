package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** NFR2: {@code POST /api/sql/validate} p95 < 300 ms through the full running service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class ValidatePerformanceIT extends AbstractIntegrationTest {

    static final int WARMUP = 10;
    static final int SAMPLES = 50;
    static final String BODY = "{\"sql_content\":\"WITH w AS (SELECT customer_id, total, row_number() OVER "
            + "(PARTITION BY customer_id ORDER BY created_at DESC) AS rn FROM orders WHERE created_at >= now() "
            + "- interval '30 days') SELECT customer_id, count(*) FILTER (WHERE total > 100) AS big FROM w "
            + "WHERE rn <= 5 GROUP BY customer_id\"}";

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private int post() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sql/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void validateP95Under300ms() throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            assertThat(post()).isEqualTo(200);
        }
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            long t0 = System.nanoTime();
            assertThat(post()).isEqualTo(200);
            times.add((System.nanoTime() - t0) / 1_000_000);
        }
        times.sort(null);
        assertThat(times.get((int) Math.ceil(SAMPLES * 0.95) - 1)).isLessThan(300);
    }
}
