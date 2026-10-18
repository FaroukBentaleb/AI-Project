package com.devassisthub.sql.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.repository.SavedQueryRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SavedQueryPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired SavedQueryRepository repository;
    @Autowired TestEntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayAppliedV1() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void roundTripsEveryField() {
        SavedQuery saved = repository.saveAndFlush(sample());
        em.clear();

        SavedQuery loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getTitle()).isEqualTo("Orders per region");
        assertThat(loaded.getDescription()).isEqualTo("Weekly orders");
        assertThat(loaded.getSqlContent()).isEqualTo("SELECT region, count(*) FROM orders GROUP BY region");
        assertThat(loaded.getTags()).containsExactly("reporting", "orders");
        assertThat(loaded.getDialect()).isEqualTo("postgresql");
        assertThat(loaded.getNotes()).isEqualTo("note");
        assertThat(loaded.getAuthor()).isEqualTo("alice");
        assertThat(loaded.isLlmGenerated()).isTrue();
        assertThat(loaded.getOriginalNlPrompt()).isEqualTo("orders per region");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void nullTagsPersistAsEmptyArray() {
        SavedQuery q = sample();
        q.setTags(null);
        SavedQuery saved = repository.saveAndFlush(q);
        em.clear();
        assertThat(repository.findById(saved.getId()).orElseThrow().getTags()).isEmpty();
    }

    @Test
    void updateIncrementsVersionAndKeepsCreatedAtAndAuthor() throws Exception {
        SavedQuery saved = repository.saveAndFlush(sample());
        em.clear();
        SavedQuery first = repository.findById(saved.getId()).orElseThrow();
        OffsetDateTime createdAt = first.getCreatedAt();
        OffsetDateTime updatedAt = first.getUpdatedAt();

        Thread.sleep(5);
        first.setTitle("Renamed");
        first.setAuthor("mallory");
        repository.saveAndFlush(first);
        em.clear();

        SavedQuery reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Renamed");
        assertThat(reloaded.getVersion()).isEqualTo(1);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getAuthor()).isEqualTo("alice");
        assertThat(reloaded.getUpdatedAt()).isAfter(updatedAt);
    }

    private static SavedQuery sample() {
        SavedQuery q = new SavedQuery();
        q.setTitle("Orders per region");
        q.setDescription("Weekly orders");
        q.setSqlContent("SELECT region, count(*) FROM orders GROUP BY region");
        q.setTags(List.of("reporting", "orders"));
        q.setNotes("note");
        q.setAuthor("alice");
        q.setLlmGenerated(true);
        q.setOriginalNlPrompt("orders per region");
        return q;
    }
}
