package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.qdrant.QdrantContainer;

/**
 * Qdrant unreachable at startup: the service boots, CRUD works, root health stays UP, the dependencies group
 * reports qdrant DOWN, and the bootstrap succeeds once a Qdrant appears on the configured port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "qdrant.bootstrap-retry=PT1S"})
// Does not extend the base: its @DynamicPropertySource would race with ours for qdrant.* keys. It reuses the
// shared PostgreSQL container instead.
class QdrantDownIT {

    private static final int LATE_PORT = freePort();
    private static final QdrantContainer LATE = new QdrantContainer(AbstractIntegrationTest.QDRANT_IMAGE)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(LATE_PORT), new ExposedPort(6333)),
                    new PortBinding(Ports.Binding.bindPort(freePort()), new ExposedPort(6334))));

    @DynamicPropertySource
    static void unreachableQdrant(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
        registry.add("qdrant.host", () -> "localhost");
        registry.add("qdrant.rest-port", () -> LATE_PORT);
        // Never reach the real Hugging Face API from tests: embed through the shared stub (Story 5.4 reconciles).
        registry.add("hf.embedding-api-token", () -> "test-token");
        registry.add("hf.embedding-model", () -> "test/stub-model");
        registry.add("hf.embedding-base-url", com.devassisthub.sql.support.StubEmbeddingServer::baseUrl);
    }

    @AfterAll
    static void stopLate() {
        LATE.stop();
    }

    @Autowired CollectionBootstrap bootstrap;
    @Autowired QdrantClient qdrant;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bootsDegradedThenBootstrapsWhenQdrantComesUp() throws Exception {
        assertThat(bootstrap.isReady()).isFalse();

        HttpResponse<String> root = send("GET", "/actuator/health", null);
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(root.body()).path("status").asText()).isEqualTo("UP");

        HttpResponse<String> deps = send("GET", "/actuator/health/dependencies", null);
        assertThat(deps.statusCode()).isEqualTo(503);
        assertThat(mapper.readTree(deps.body()).path("components").path("qdrant").path("status").asText())
                .isEqualTo("DOWN");

        // CRUD unaffected.
        HttpResponse<String> created = send("POST", "/api/sql/queries",
                "{\"title\":\"qdrant down\",\"description\":\"d\",\"sql_content\":\"SELECT 1\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        // Story 5.3: the save still succeeds, not indexed, with a Warning.
        JsonNode body = mapper.readTree(created.body());
        assertThat(body.path("indexed").asBoolean()).isFalse();
        assertThat(body.path("warnings").get(0).asText()).startsWith(QueryIndexer.WARNING_PREFIX);
        assertThat(send("GET", "/api/sql/queries", null).statusCode()).isEqualTo(200);
        long id = body.path("id").asLong();

        // Story 5.4: on-demand reindex while Qdrant is down is a 503 in the standard envelope.
        HttpResponse<String> reindex = send("POST", "/api/sql/admin/reindex", null);
        assertThat(reindex.statusCode()).isEqualTo(503);
        JsonNode err = mapper.readTree(reindex.body());
        assertThat(err.path("error").asText()).isEqualTo("RAG_INDEX_UNAVAILABLE");
        assertThat(err.path("message").asText()).startsWith("RAG index unavailable");
        assertThat(err.has("field")).isTrue();

        LATE.start();
        Instant up = Instant.now();
        while (!bootstrap.isReady() && Duration.between(up, Instant.now()).toSeconds() < 30) {
            Thread.sleep(200);
        }
        assertThat(bootstrap.isReady()).isTrue();

        JsonNode depsUp = mapper.readTree(send("GET", "/actuator/health/dependencies", null).body());
        assertThat(depsUp.path("components").path("qdrant").path("status").asText()).isEqualTo("UP");
        assertThat(depsUp.path("components").path("qdrant").path("details").path("vector_size").asInt())
                .isEqualTo(384);

        // Story 5.4: the delayed bootstrap triggers Reconciliation, which indexes the query saved while down.
        Instant start = Instant.now();
        while (!indexed(id) && Duration.between(start, Instant.now()).toSeconds() < 30) {
            Thread.sleep(200);
        }
        assertThat(indexed(id)).isTrue();
        assertThat(qdrant.getPointPayload(id)).isPresent();
    }

    private boolean indexed(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT indexed FROM saved_query WHERE id = ?",
                Boolean.class, id));
    }
}
