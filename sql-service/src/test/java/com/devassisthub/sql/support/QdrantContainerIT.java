package com.devassisthub.sql.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"eureka.client.enabled=false"})
class QdrantContainerIT extends AbstractIntegrationTest {

    @Value("${qdrant.host}") String host;
    @Value("${qdrant.rest-port}") int restPort;
    @Value("${qdrant.port}") int grpcPort;

    @Test
    void qdrantListsCollections() throws Exception {
        assertThat(QDRANT.isRunning()).isTrue();
        assertThat(grpcPort).isEqualTo(QDRANT.getMappedPort(6334));
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://" + host + ":" + restPort + "/collections")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"ok\"").contains("\"collections\"");
    }
}
