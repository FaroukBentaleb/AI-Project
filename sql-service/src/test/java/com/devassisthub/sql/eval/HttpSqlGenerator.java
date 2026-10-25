package com.devassisthub.sql.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Story 6.6: calls {@code POST /api/sql/generate} on running sql-service instances. RAG is a server setting
 * ({@code sql.rag.enabled}), so RAG on and RAG off are two base URLs (two instances, or one instance restarted).
 * The HF token stays inside sql-service; the harness never sees it.
 */
public final class HttpSqlGenerator implements SqlGenerator {

    private final String ragOnUrl;
    private final String ragOffUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public HttpSqlGenerator(String ragOnUrl, String ragOffUrl) {
        this.ragOnUrl = ragOnUrl;
        this.ragOffUrl = ragOffUrl;
    }

    @Override
    public Generation generate(String prompt, boolean rag) {
        String base = (rag ? ragOnUrl : ragOffUrl).replaceAll("/+$", "");
        try {
            String body = json.writeValueAsString(Map.of("prompt", prompt, "dialect", "postgresql"));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/sql/generate"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-User-Id", "eval-harness")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.readTree(res.body());
            if (res.statusCode() == 200 && node.hasNonNull("sql")) {
                return Generation.ok(node.get("sql").asText());
            }
            return Generation.failed(node.hasNonNull("error") ? node.get("error").asText() : "HTTP_" + res.statusCode());
        } catch (Exception e) {
            return Generation.failed("TRANSPORT_" + e.getClass().getSimpleName());
        }
    }
}
