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
import com.devassisthub.sql.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class SavedQuerySearchIT extends AbstractIntegrationTest {


    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired SavedQueryRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        save("Weekly orders by region", "Sums revenue", List.of("reporting"), "alice", false);
        save("Top customers", "Best buyers by spend", List.of("ops"), "bob", false);
        save("Daily churn", "Lost ORDERS per day", List.of("finance", "ops"), "alice", true);
        save("Order_count 100%", "literal wildcard chars", List.of(), "carol", true);
    }

    private void save(String title, String desc, List<String> tags, String author, boolean llm) {
        SavedQuery q = new SavedQuery();
        q.setTitle(title);
        q.setDescription(desc);
        q.setSqlContent("SELECT 1");
        q.setTags(tags);
        q.setAuthor(author);
        q.setLlmGenerated(llm);
        repository.saveAndFlush(q);
    }

    private JsonNode get(String url, HttpStatus expected, Object... vars) throws Exception {
        ResponseEntity<String> r = rest.getForEntity(url, String.class, vars);
        assertThat(r.getStatusCode()).isEqualTo(expected);
        return mapper.readTree(r.getBody());
    }

    private List<String> titles(String url, Object... vars) throws Exception {
        List<String> t = new ArrayList<>();
        get(url, HttpStatus.OK, vars).get("content").forEach(n -> t.add(n.get("title").asText()));
        return t;
    }

    @Test
    void qMatchesTitleOrDescriptionCaseInsensitively() throws Exception {
        assertThat(titles("/api/sql/queries?q=ORDERS"))
                .containsExactlyInAnyOrder("Weekly orders by region", "Daily churn");
        assertThat(titles("/api/sql/queries?q=customers")).containsExactly("Top customers");
        assertThat(titles("/api/sql/queries?q=nothing-matches")).isEmpty();
    }

    @Test
    void likeWildcardsInQAreLiteral() throws Exception {
        assertThat(titles("/api/sql/queries?q={q}", "100%")).containsExactly("Order_count 100%");
        assertThat(titles("/api/sql/queries?q=r_c")).containsExactly("Order_count 100%");
    }

    @Test
    void tagsMatchAny() throws Exception {
        assertThat(titles("/api/sql/queries?tags=reporting,finance"))
                .containsExactlyInAnyOrder("Weekly orders by region", "Daily churn");
        assertThat(titles("/api/sql/queries?tags=ops")).containsExactlyInAnyOrder("Top customers", "Daily churn");
        assertThat(titles("/api/sql/queries?tags=unknown")).isEmpty();
    }

    @Test
    void authorAndLlmGeneratedFilters() throws Exception {
        assertThat(titles("/api/sql/queries?author=alice"))
                .containsExactlyInAnyOrder("Weekly orders by region", "Daily churn");
        assertThat(titles("/api/sql/queries?author=ALICE")).isEmpty();
        assertThat(titles("/api/sql/queries?llm_generated=true"))
                .containsExactlyInAnyOrder("Daily churn", "Order_count 100%");
        assertThat(titles("/api/sql/queries?llm_generated=false"))
                .containsExactlyInAnyOrder("Weekly orders by region", "Top customers");
    }

    @Test
    void allFiltersCombineWithAndAndKeepPaging() throws Exception {
        assertThat(titles("/api/sql/queries?q=orders&tags=finance,reporting&author=alice&llm_generated=true"))
                .containsExactly("Daily churn");
        JsonNode p0 = get("/api/sql/queries?author=alice&q=orders&size=1&sort=title,asc", HttpStatus.OK);
        assertThat(p0.get("total_elements").asLong()).isEqualTo(2);
        assertThat(p0.get("total_pages").asInt()).isEqualTo(2);
        assertThat(p0.get("content").get(0).get("title").asText()).isEqualTo("Daily churn");
        JsonNode p1 = get("/api/sql/queries?author=alice&q=orders&size=1&page=1&sort=title,asc", HttpStatus.OK);
        assertThat(p1.get("content").get(0).get("title").asText()).isEqualTo("Weekly orders by region");
    }

    @Test
    void invalidFilterIs400NotServerError() throws Exception {
        JsonNode body = get("/api/sql/queries?llm_generated=maybe", HttpStatus.BAD_REQUEST);
        assertThat(body.get("error").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("field").asText()).isEqualTo("llm_generated");
    }

    @Test
    void v2MigrationAppliedWithIndexes() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).startsWith("1", "2");
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'saved_query'", String.class))
                .contains("idx_saved_query_author", "idx_saved_query_created_at", "idx_saved_query_tags");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_saved_query_tags'",
                String.class)).containsIgnoringCase("gin");
    }
}
