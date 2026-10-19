package com.devassisthub.sql.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.rag.QdrantClient;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Story 5.5: seeding on startup (enabled explicitly; tests default to sql.seed.enabled=false). */
@SpringBootTest(properties = {"eureka.client.enabled=false", "sql.seed.enabled=true"})
class SeedCorpusIT extends AbstractIntegrationTest {

    @Autowired SeedCorpusSeeder seeder;
    @Autowired QdrantClient qdrant;
    @Autowired JdbcTemplate jdbc;

    private List<Map<String, Object>> seeds() {
        return jdbc.queryForList("SELECT id, seed_key, title, description, tags, author, read_only, indexed, deleted "
                + "FROM saved_query WHERE seed_key IS NOT NULL ORDER BY id");
    }

    @Test
    void startupSeedsIndexesAndRestartsWithoutDuplicatesOrOverwrites() {
        int size = SeedCorpus.load().size();
        List<Map<String, Object>> rows = seeds();
        assertThat(rows).hasSize(size);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("author")).isEqualTo("seed");
            assertThat((String) r.get("title")).isNotBlank();
            assertThat((String) r.get("description")).isNotBlank();
            assertThat(r.get("read_only")).isEqualTo(true);
            assertThat(r.get("indexed")).isEqualTo(true);
            assertThat(qdrant.getPointPayload(((Number) r.get("id")).longValue())).isPresent();
        });

        // A user edits one seed and deletes another; a restart must respect both.
        long edited = ((Number) rows.get(0).get("id")).longValue();
        long deleted = ((Number) rows.get(1).get("id")).longValue();
        jdbc.update("UPDATE saved_query SET title = 'Team edit' WHERE id = ?", edited);
        jdbc.update("UPDATE saved_query SET deleted = true, deleted_at = now() WHERE id = ?", deleted);

        assertThat(seeder.seed()).isZero();

        assertThat(seeds()).hasSize(size);
        assertThat(jdbc.queryForObject("SELECT title FROM saved_query WHERE id = ?", String.class, edited))
                .isEqualTo("Team edit");
        assertThat(jdbc.queryForObject("SELECT deleted FROM saved_query WHERE id = ?", Boolean.class, deleted))
                .isTrue();
    }
}
