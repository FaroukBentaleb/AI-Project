package com.devassisthub.sql.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Story 6.6: the REAL evaluation (SM-2, SM-3). Skipped unless {@code -Deval.run=true}, which only the Maven
 * {@code eval} profile sets, so normal {@code mvn verify} / CI never runs it. Calls running sql-service instances
 * (which hold the HF credentials and model); generated SQL only ever runs in the throwaway {@link EvalDatabase}.
 *
 * <p>Properties: {@code eval.rag-on.url} (default http://localhost:8084), {@code eval.rag-off.url} (default: same,
 * i.e. the caller must point it at an instance started with SQL_RAG_ENABLED=false), {@code eval.runs} (3),
 * {@code eval.statement-timeout-ms} (5000), {@code eval.report} (target/eval-report.md).
 */
@EnabledIfSystemProperty(named = "eval.run", matches = "true")
class SqlEvalIT {

    @Test
    void evaluateRagOnVersusOff() throws Exception {
        String on = System.getProperty("eval.rag-on.url", "http://localhost:8084");
        String off = System.getProperty("eval.rag-off.url", on);
        int runs = Integer.getInteger("eval.runs", 3);
        int timeoutMs = Integer.getInteger("eval.statement-timeout-ms", 5000);
        Path out = Path.of(System.getProperty("eval.report", "target/eval-report.md"));

        try (EvalDatabase db = new EvalDatabase(timeoutMs).start()) {
            EvalHarness.Report report = EvalHarness.run("RAG on " + on + ", RAG off " + off,
                    EvalPrompts.load(), runs, new HttpSqlGenerator(on, off), db::tryExecute);
            report.write(out);
            System.out.println(report.toMarkdown());
            assertThat(report.attempts()).hasSize(EvalPrompts.load().size() * 2 * runs);
        }
    }
}
