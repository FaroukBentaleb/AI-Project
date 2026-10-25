package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.exception.LlmFailureException;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import com.devassisthub.sql.support.StubEmbeddingServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Story 6.2 (FR-19): with no HF_API_TOKEN the service boots, CRUD and validate work, generate is 503. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class NoLlmTokenIT {

    @DynamicPropertySource
    static void noToken(DynamicPropertyRegistry r) {
        // Same shared containers as AbstractIntegrationTest, but no HF token (the base class always sets one).
        r.add("spring.datasource.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        r.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
        r.add("qdrant.host", AbstractIntegrationTest.QDRANT::getHost);
        r.add("qdrant.port", () -> AbstractIntegrationTest.QDRANT.getMappedPort(6334));
        r.add("qdrant.rest-port", () -> AbstractIntegrationTest.QDRANT.getMappedPort(6333));
        r.add("hf.embedding-base-url", StubEmbeddingServer::baseUrl);
        r.add("groq.api-key", () -> "");
        // Closed port: if anything did call out, it would surface as LLM_UNAVAILABLE and fail the test.
        r.add("groq.base-url", () -> "http://127.0.0.1:9/models");
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    private HttpEntity<String> json(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-User-Id", "alice");
        return new HttpEntity<>(body, h);
    }

    @Test
    void crudAndValidateWorkWhileGenerateIsNotConfigured() throws Exception {
        ResponseEntity<String> created = rest.postForEntity("/api/sql/queries",
                json("{\"title\":\"No token\",\"description\":\"d\",\"sql_content\":\"SELECT 1\"}"), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode c = mapper.readTree(created.getBody());
        long id = c.get("id").asLong();

        assertThat(rest.getForEntity("/api/sql/queries/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/api/sql/queries", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> edited = rest.exchange("/api/sql/queries/" + id, HttpMethod.PUT,
                json("{\"title\":\"No token 2\",\"description\":\"d\",\"sql_content\":\"SELECT 2\",\"version\":"
                        + c.get("version").asInt() + "}"), String.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/sql/queries/" + id, HttpMethod.DELETE, json(null), String.class)
                .getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(rest.postForEntity("/api/sql/validate", json("{\"sql_content\":\"SELECT 1\"}"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> gen = rest.postForEntity("/api/sql/generate", json("{\"prompt\":\"orders\"}"),
                String.class);
        assertThat(gen.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        JsonNode g = mapper.readTree(gen.getBody());
        assertThat(g.get("error").asText()).isEqualTo("LLM_NOT_CONFIGURED");
        assertThat(g.get("message").asText()).isEqualTo(LlmFailureException.NOT_CONFIGURED_MESSAGE);
        assertThat(gen.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }
}
