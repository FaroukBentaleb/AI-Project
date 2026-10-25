package com.devassisthub.sql.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.support.AbstractIntegrationTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Story 5.5: every seed query runs against {@code seed/schema.sql}. Uses its OWN throwaway PostgreSQL, never sql_db
 * nor the shared IT database; each query is run with EXPLAIN ANALYZE inside a read-only transaction.
 */
class SeedSchemaIT {

    private static final PostgreSQLContainer<?> EVAL_DB =
            new PostgreSQLContainer<>(AbstractIntegrationTest.POSTGRES_IMAGE).withDatabaseName("seed_eval");

    @BeforeAll
    static void loadSchema() throws Exception {
        EVAL_DB.start();
        String ddl;
        try (InputStream in = SeedSchemaIT.class.getClassLoader().getResourceAsStream(SeedCorpus.SCHEMA_RESOURCE)) {
            assertThat(in).as(SeedCorpus.SCHEMA_RESOURCE).isNotNull();
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(ddl);
        }
    }

    @AfterAll
    static void stop() {
        EVAL_DB.stop();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(EVAL_DB.getJdbcUrl(), EVAL_DB.getUsername(), EVAL_DB.getPassword());
    }

    @Test
    void sampleDataCoversLastWeekAndInactiveCustomers() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT "
                     + "(SELECT COUNT(*) FROM orders o WHERE o.created_at >= date_trunc('week', now()) - interval '1 week'"
                     + " AND o.created_at < date_trunc('week', now())), "
                     + "(SELECT COUNT(*) FROM customers c WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id)), "
                     + "(SELECT COUNT(*) FROM orders WHERE total <= 0)")) {
            rs.next();
            assertThat(rs.getLong(1)).isPositive();
            assertThat(rs.getLong(2)).isPositive();
            assertThat(rs.getLong(3)).isZero();
        }
    }

    @Test
    void everySeedQueryRunsReadOnlyAgainstTheSeedSchema() throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            for (SeedEntry e : SeedCorpus.load()) {
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("EXPLAIN (ANALYZE) " + e.sqlContent())) {
                    assertThat(rs.next()).as(e.seedKey()).isTrue();
                } catch (Exception ex) {
                    throw new AssertionError("Seed '" + e.seedKey() + "' failed against seed/schema.sql: "
                            + ex.getMessage(), ex);
                } finally {
                    c.rollback();
                }
            }
        }
    }
}
