package com.devassisthub.sql.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.eval.EvalPrompts.EvalPrompt;
import com.devassisthub.sql.eval.SqlGenerator.Generation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story 6.6: offline self-test of the harness (stubbed LLM, no HF call). Scoring, the read-only sandbox on a
 * throwaway PostgreSQL loaded with seed/schema.sql, and the report. Runs in normal {@code mvn verify}.
 */
class EvalHarnessSelfIT {

    private static EvalDatabase db;

    @BeforeAll
    static void start() throws Exception {
        db = new EvalDatabase(1000).start();
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @Test
    void validSelectExecutes() {
        assertThat(db.tryExecute("SELECT c.country, COUNT(*) FROM customers c GROUP BY c.country")).isNull();
    }

    @Test
    void unknownColumnIsNotExecutable() {
        assertThat(db.tryExecute("SELECT revenue FROM orders")).contains("42703");
    }

    @Test
    void writesAreBlockedAndLeaveNoTrace() throws Exception {
        assertThat(db.tryExecute("DELETE FROM order_items")).isNotNull();
        assertThat(db.tryExecute("UPDATE products SET price = 0")).isNotNull();
        assertThat(db.tryExecute("COMMIT; DELETE FROM order_items")).isNotNull();
        assertThat(db.tryExecute("SET TRANSACTION READ WRITE; DELETE FROM order_items")).isNotNull();
        try (Connection c = db.admin(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT (SELECT COUNT(*) FROM order_items), "
                     + "(SELECT COUNT(*) FROM products WHERE price = 0)")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1800);
            assertThat(rs.getLong(2)).isZero();
        }
    }

    @Test
    void statementTimeoutStopsSlowQueries() {
        long t0 = System.nanoTime();
        assertThat(db.tryExecute("SELECT pg_sleep(10)")).contains("57014");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(8000);
    }

    @Test
    void scoresRagOnVersusOffAndWritesReport(@TempDir Path dir) throws Exception {
        List<EvalPrompt> prompts = List.of(new EvalPrompt("T1", "count customers"),
                new EvalPrompt("T2", "list products"));
        AtomicInteger calls = new AtomicInteger();
        // RAG on: always valid SQL. RAG off: T1 valid, T2 hallucinated column; run 3 of RAG off for T1 fails upstream.
        SqlGenerator stub = (prompt, rag) -> {
            int n = calls.incrementAndGet();
            if (rag) {
                return Generation.ok(prompt.startsWith("count") ? "SELECT COUNT(*) FROM customers"
                        : "SELECT name, price FROM products");
            }
            if (prompt.startsWith("count")) {
                return n > 8 ? Generation.failed("LLM_TIMEOUT") : Generation.ok("SELECT COUNT(*) FROM customers");
            }
            return Generation.ok("SELECT name, colour FROM products");
        };

        EvalHarness.Report r = EvalHarness.run("stub", prompts, 3, stub, db::tryExecute);

        assertThat(calls).hasValue(12);
        assertThat(r.attempts()).hasSize(12);
        assertThat(r.rate(true)).isEqualTo(100.0);
        assertThat(r.rate(false)).isCloseTo(100.0 / 3, org.assertj.core.data.Offset.offset(0.01));
        assertThat(r.deltaPoints()).isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));

        Path file = r.write(dir.resolve("eval-report.md"));
        String md = Files.readString(file);
        assertThat(md).contains("| RAG on | 6 | 6 | 100.0% |", "| RAG off | 2 | 6 | 33.3% |",
                "**RAG lift: +66.7 percentage points**", "| T1 | count customers | YYY | YYN |",
                "| T2 | list products | YYY | NNN |", "T1 RAG off run 3: generation LLM_TIMEOUT",
                "T2 RAG off run 1: execution 42703");
    }

    @Test
    void realPromptFileRunsThroughTheHarness() {
        List<EvalPrompt> prompts = EvalPrompts.load();
        EvalHarness.Report r = EvalHarness.run("stub", prompts, 3,
                (p, rag) -> Generation.ok("SELECT 1"), db::tryExecute);
        assertThat(r.attempts()).hasSize(90);
        assertThat(r.rate(true)).isEqualTo(100.0);
        assertThat(r.deltaPoints()).isZero();
    }
}
