package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.repository.SavedQueryRepository;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/** Story 8.5 (FR35): export against real PostgreSQL — list filters honoured, deleted never exported. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false"})
class SavedQueryExportIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired SavedQueryRepository repository;

    private SavedQuery row(String title, String author, List<String> tags, String sql) {
        SavedQuery q = new SavedQuery();
        q.setTitle(title);
        q.setDescription("about " + title);
        q.setAuthor(author);
        q.setTags(tags);
        q.setSqlContent(sql);
        return q;
    }

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.saveAndFlush(row("Active users", "alice", List.of("users"), "SELECT id FROM users -- live"));
        SavedQuery old = row("Old report", "bob", List.of("reports"), "SELECT 2;");
        ReflectionTestUtils.setField(old, "deprecated", true);
        ReflectionTestUtils.setField(old, "deprecatedReason", "use v2 */ please");
        repository.saveAndFlush(old);
        SavedQuery gone = row("Deleted one", "alice", List.of("users"), "SELECT 3");
        ReflectionTestUtils.setField(gone, "deleted", true);
        repository.saveAndFlush(gone);
    }

    private ResponseEntity<String> export(String query) {
        return rest.getForEntity("/api/sql/queries/export" + query, String.class);
    }

    @Test
    void exportsAllLiveQueriesNeverDeleted() {
        ResponseEntity<String> r = export("?sort=title,asc");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
        assertThat(r.getHeaders().getContentDisposition().getFilename()).matches("sql-library-.*\\.sql");
        String body = r.getBody();
        assertThat(body).contains("-- 2 queries.");
        assertThat(body).doesNotContain("Deleted one").doesNotContain("SELECT 3");
        assertThat(body.indexOf("-- Title: Active users")).isLessThan(body.indexOf("-- Title: Old report"));
        assertThat(body).contains("SELECT id FROM users -- live\n;\n");
        assertThat(body).contains("-- Deprecated: use v2 * / please\nSELECT 2;\n");
    }

    @Test
    void honoursListFilters() {
        String body = export("?deprecated=false").getBody();
        assertThat(body).contains("-- 1 query.").contains("Active users").doesNotContain("Old report");
        body = export("?tags=reports&author=bob").getBody();
        assertThat(body).contains("Old report").doesNotContain("Active users");
        body = export("?q=nothing-matches").getBody();
        assertThat(body).contains("-- 0 queries.").doesNotContain("-- Title:");
    }

    @Test
    void invalidFilterIs400() {
        assertThat(export("?read_only=perhaps").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
