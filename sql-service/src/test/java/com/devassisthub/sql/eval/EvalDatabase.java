package com.devassisthub.sql.eval;

import com.devassisthub.sql.seed.SeedCorpus;
import com.devassisthub.sql.support.AbstractIntegrationTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Story 6.6 (addendum §H): a throwaway PostgreSQL loaded with {@code seed/schema.sql}. Never sql_db. Generated SQL
 * runs as role {@code eval_ro} (SELECT-only grants, read-only by default) inside a READ ONLY transaction with a
 * {@code statement_timeout}, and is always rolled back.
 */
public final class EvalDatabase implements AutoCloseable {

    static final String RO_USER = "eval_ro";
    private static final String RO_PASSWORD = "eval_ro";

    private final PostgreSQLContainer<?> container =
            new PostgreSQLContainer<>(AbstractIntegrationTest.POSTGRES_IMAGE).withDatabaseName("sql_eval");
    private final int statementTimeoutMs;

    public EvalDatabase(int statementTimeoutMs) {
        this.statementTimeoutMs = statementTimeoutMs;
    }

    public EvalDatabase start() throws Exception {
        container.start();
        String ddl;
        try (InputStream in = EvalDatabase.class.getClassLoader().getResourceAsStream(SeedCorpus.SCHEMA_RESOURCE)) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute(ddl);
            s.execute("CREATE ROLE " + RO_USER + " LOGIN PASSWORD '" + RO_PASSWORD + "'");
            s.execute("GRANT USAGE ON SCHEMA public TO " + RO_USER);
            s.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + RO_USER);
            s.execute("ALTER ROLE " + RO_USER + " SET default_transaction_read_only = on");
            s.execute("ALTER ROLE " + RO_USER + " SET statement_timeout = " + statementTimeoutMs);
        }
        return this;
    }

    /** Superuser connection, for setup and test assertions only; never used for generated SQL. */
    public Connection admin() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /** Runs one generated statement read-only; returns {@code null} when it executed, else the error. */
    public String tryExecute(String sql) {
        try (Connection c = DriverManager.getConnection(container.getJdbcUrl(), RO_USER, RO_PASSWORD)) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            try (Statement s = c.createStatement()) {
                s.execute("SET TRANSACTION READ ONLY");
                s.execute("SET LOCAL statement_timeout = " + statementTimeoutMs);
                s.setMaxRows(1000);
                s.execute(sql);
                return null;
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            String state = e.getSQLState() == null ? "" : e.getSQLState() + " ";
            return state + String.valueOf(e.getMessage()).lines().findFirst().orElse("");
        }
    }

    @Override
    public void close() {
        container.stop();
    }
}
