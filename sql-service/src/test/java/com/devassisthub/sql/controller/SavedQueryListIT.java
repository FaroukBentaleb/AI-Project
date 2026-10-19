package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.repository.SavedQueryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
@Testcontainers
class SavedQueryListIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired SavedQueryRepository repository;

    private final List<Long> ids = new ArrayList<>();

    @BeforeEach
    void seed() {
        repository.deleteAll();
        ids.clear();
        String[][] rows = {{"Charlie", "alice"}, {"alpha", "bob"}, {"Bravo", "carol"}};
        for (String[] r : rows) {
            SavedQuery q = new SavedQuery();
            q.setTitle(r[0]);
            q.setDescription("d");
            q.setSqlContent("SELECT 1");
            q.setAuthor(r[1]);
            ids.add(repository.saveAndFlush(q).getId());
        }
    }

    private JsonNode get(String url, HttpStatus expected) throws Exception {
        ResponseEntity<String> r = rest.getForEntity(url, String.class);
        assertThat(r.getStatusCode()).isEqualTo(expected);
        return mapper.readTree(r.getBody());
    }

    private static List<String> titles(JsonNode page) {
        List<String> t = new ArrayList<>();
        page.get("content").forEach(n -> t.add(n.get("title").asText()));
        return t;
    }

    @Test
    void defaultListReturnsAllAuthorsNewestFirstWithMetadata() throws Exception {
        JsonNode body = get("/api/sql/queries", HttpStatus.OK);
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("total_elements").asLong()).isEqualTo(3);
        assertThat(body.get("total_pages").asInt()).isEqualTo(1);
        List<Long> got = new ArrayList<>();
        body.get("content").forEach(n -> got.add(n.get("id").asLong()));
        assertThat(got).containsExactly(ids.get(2), ids.get(1), ids.get(0));
        List<String> authors = new ArrayList<>();
        body.get("content").forEach(n -> authors.add(n.get("author").asText()));
        assertThat(authors).containsExactlyInAnyOrder("alice", "bob", "carol");
        assertThat(body.get("content").get(0).get("sql_content").asText()).isEqualTo("SELECT 1");
    }

    @Test
    void sizeIsClampedTo100() throws Exception {
        assertThat(get("/api/sql/queries?size=500", HttpStatus.OK).get("size").asInt()).isEqualTo(100);
    }

    @Test
    void pagingSplitsResults() throws Exception {
        JsonNode p1 = get("/api/sql/queries?page=1&size=2", HttpStatus.OK);
        assertThat(p1.get("page").asInt()).isEqualTo(1);
        assertThat(p1.get("total_pages").asInt()).isEqualTo(2);
        assertThat(p1.get("content")).hasSize(1);
        assertThat(p1.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(0));
        assertThat(get("/api/sql/queries?page=5", HttpStatus.OK).get("content")).isEmpty();
    }

    @Test
    void sortByTitleAscAndDescOnRealDatabase() throws Exception {
        assertThat(titles(get("/api/sql/queries?sort=title,asc", HttpStatus.OK)))
                .containsExactly("alpha", "Bravo", "Charlie");
        assertThat(titles(get("/api/sql/queries?sort=title,desc", HttpStatus.OK)))
                .containsExactly("Charlie", "Bravo", "alpha");
        assertThat(get("/api/sql/queries?sort=updated_at", HttpStatus.OK).get("content")).hasSize(3);
    }

    @Test
    void unknownSortIs400NotServerError() throws Exception {
        for (String sort : new String[] {"author", "use_count", "nope", "createdAt", "title,up"}) {
            JsonNode body = get("/api/sql/queries?sort=" + sort, HttpStatus.BAD_REQUEST);
            assertThat(body.get("error").asText()).as(sort).isEqualTo("VALIDATION_FAILED");
            assertThat(body.get("field").asText()).as(sort).isEqualTo("sort");
        }
    }
}
