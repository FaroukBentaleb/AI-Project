package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
@Testcontainers
class SavedQueryApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    @Test
    void postThenGetReturnsSameSavedQuery() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", "alice");
        String body = """
                {"title":"Orders","description":"Weekly","sql_content":"SELECT 1","tags":["r"],
                 "is_llm_generated":true,"original_nl_prompt":"orders"}""";
        ResponseEntity<String> created =
                rest.postForEntity("/api/sql/queries", new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode c = mapper.readTree(created.getBody());
        long id = c.get("id").asLong();
        assertThat(created.getHeaders().getLocation()).hasToString("/api/sql/queries/" + id);
        assertThat(c.get("author").asText()).isEqualTo("alice");
        assertThat(c.get("dialect").asText()).isEqualTo("postgresql");
        assertThat(c.get("version").asInt()).isZero();
        assertThat(c.get("created_at").isNull()).isFalse();

        ResponseEntity<String> fetched = rest.getForEntity("/api/sql/queries/" + id, String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode f = mapper.readTree(fetched.getBody());
        for (String k : new String[] {"id", "title", "description", "sql_content", "tags", "dialect", "notes",
                "author", "is_llm_generated", "original_nl_prompt", "version", "warnings"}) {
            assertThat(f.get(k)).as(k).isEqualTo(c.get(k));
        }
        for (String k : new String[] {"created_at", "updated_at"}) {
            assertThat(OffsetDateTime.parse(f.get(k).asText()).toInstant()).as(k)
                    .isEqualTo(OffsetDateTime.parse(c.get(k).asText()).toInstant());
        }
    }

    @Test
    void unknownIdIs404() throws Exception {
        ResponseEntity<String> r = rest.getForEntity("/api/sql/queries/987654", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mapper.readTree(r.getBody()).get("error").asText()).isEqualTo("NOT_FOUND");
    }
}
