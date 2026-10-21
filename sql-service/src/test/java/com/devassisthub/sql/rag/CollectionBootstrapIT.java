package com.devassisthub.sql.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** FR-21 against a real Qdrant (shared Testcontainer). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class CollectionBootstrapIT extends AbstractIntegrationTest {

    private static final String FOREIGN = "commit_embeddings_it";

    @Autowired QdrantClient client;
    @Autowired CollectionBootstrap appBootstrap;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private String qdrant() {
        return "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333);
    }

    private HttpResponse<String> send(String method, String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode collection(String name) throws Exception {
        HttpResponse<String> r = send("GET", qdrant() + "/collections/" + name, null);
        assertThat(r.statusCode()).isEqualTo(200);
        return mapper.readTree(r.body()).path("result");
    }

    private void upsertPoint(String name, int dim) throws Exception {
        StringBuilder v = new StringBuilder();
        for (int i = 0; i < dim; i++) {
            v.append(i == 0 ? "" : ",").append(i == 0 ? "1.0" : "0.0");
        }
        HttpResponse<String> r = send("PUT", qdrant() + "/collections/" + name + "/points?wait=true",
                "{\"points\":[{\"id\":1,\"vector\":[" + v + "],\"payload\":{\"k\":\"v\"}}]}");
        assertThat(r.statusCode()).isEqualTo(200);
    }

    @Test
    void appStartupCreatedCollectionWithConfiguredSizeAndCosine() throws Exception {
        assertThat(appBootstrap.isReady()).isTrue();
        JsonNode vectors = collection("sql_embeddings").path("config").path("params").path("vectors");
        assertThat(vectors.path("size").asInt()).isEqualTo(384);
        assertThat(vectors.path("distance").asText()).isEqualTo("Cosine");
    }

    @Test
    void emptyQdrantThenRestartKeepsPointsAndLeavesOtherCollectionsAlone() throws Exception {
        // Simulate an empty sql_embeddings plus another service's collection with data.
        send("DELETE", qdrant() + "/collections/sql_embeddings", null);
        send("DELETE", qdrant() + "/collections/" + FOREIGN, null);
        assertThat(send("PUT", qdrant() + "/collections/" + FOREIGN,
                "{\"vectors\":{\"size\":4,\"distance\":\"Dot\"}}").statusCode()).isEqualTo(200);
        upsertPoint(FOREIGN, 4);

        // "Startup" against a Qdrant without sql_embeddings.
        assertThat(new CollectionBootstrap(client, 384).tryBootstrap()).isTrue();
        JsonNode created = collection("sql_embeddings");
        assertThat(created.path("config").path("params").path("vectors").path("size").asInt()).isEqualTo(384);
        upsertPoint("sql_embeddings", 384);

        // "Restart": a fresh bootstrap leaves the collection and its points intact.
        CollectionBootstrap restarted = new CollectionBootstrap(client, 384);
        assertThat(restarted.tryBootstrap()).isTrue();
        assertThat(restarted.sizeMismatch()).isNull();
        assertThat(collection("sql_embeddings").path("points_count").asLong()).isEqualTo(1);

        JsonNode foreign = collection(FOREIGN);
        assertThat(foreign.path("points_count").asLong()).isEqualTo(1);
        assertThat(foreign.path("config").path("params").path("vectors").path("size").asInt()).isEqualTo(4);
        assertThat(foreign.path("config").path("params").path("vectors").path("distance").asText()).isEqualTo("Dot");
        send("DELETE", qdrant() + "/collections/" + FOREIGN, null);
    }

    @Test
    void healthGroupsSplitDependenciesFromDefault() throws Exception {
        HttpResponse<String> deps = send("GET", "http://localhost:" + port + "/actuator/health/dependencies", null);
        assertThat(deps.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(deps.body());
        assertThat(body.path("components").path("qdrant").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("qdrant").path("details").path("collection").asText())
                .isEqualTo("sql_embeddings");

        HttpResponse<String> root = send("GET", "http://localhost:" + port + "/actuator/health", null);
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(root.body()).path("status").asText()).isEqualTo("UP");
    }
}
