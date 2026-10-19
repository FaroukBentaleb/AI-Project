package com.devassisthub.sql.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReadOnlyValidatorTest {

    private final ReadOnlyValidator validator = new ReadOnlyValidator();

    static final String WOW_UNION = """
            SELECT 'this_week' AS period, count(*) AS orders FROM orders
             WHERE created_at >= date_trunc('week', now())
            UNION ALL
            SELECT 'last_week', count(*) FROM orders
             WHERE created_at >= date_trunc('week', now()) - interval '7 days'
               AND created_at < date_trunc('week', now())""";

    static final String WOW_CTE = """
            WITH this_week AS (
              SELECT count(*) AS n FROM orders WHERE created_at >= date_trunc('week', now())
            ), last_week AS (
              SELECT count(*) AS n FROM orders
               WHERE created_at >= date_trunc('week', now()) - interval '7 days'
                 AND created_at < date_trunc('week', now())
            )
            SELECT (SELECT n FROM this_week) AS this_week, (SELECT n FROM last_week) AS last_week""";

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "SELECT 1;",
            "SELECT id, total FROM orders WHERE status = 'paid' ORDER BY total DESC LIMIT 10",
            "SELECT id FROM orders UNION SELECT id FROM archived_orders",
            "SELECT id FROM orders INTERSECT SELECT id FROM refunds EXCEPT SELECT id FROM test_orders",
            WOW_UNION,
            WOW_CTE,
            "SELECT o.id FROM (SELECT id FROM orders) o JOIN (SELECT id FROM customers) c ON o.id = c.id",
            "SELECT id, (SELECT max(total) FROM orders) FROM customers WHERE id IN (SELECT customer_id FROM orders)",
            "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 5) SELECT n FROM t",
            "SELECT region, count(*) FILTER (WHERE status = 'paid') FROM orders GROUP BY region",
            "SELECT DISTINCT ON (customer_id) customer_id, total FROM orders ORDER BY customer_id, created_at DESC",
            "SELECT id, rank() OVER (PARTITION BY region ORDER BY total DESC) FROM orders",
            "SELECT 'drop table orders; update x' AS note"
    })
    void acceptsSelectVariants(String sql) {
        ValidationResult result = validator.validate(sql);
        assertThat(result.rejections()).isEmpty();
        assertThat(result.accepted()).isTrue();
        assertThat(result.warnings()).isEmpty();
    }

    static Stream<Arguments> rejectedFixtures() {
        return Stream.of(
                // FR-11 fixtures
                Arguments.of("WITH x AS (INSERT INTO audit(id) VALUES (1) RETURNING id) SELECT 1",
                        Set.of(RejectionCode.DATA_MODIFYING_CTE)),
                Arguments.of("SELECT * INTO t FROM orders", Set.of(RejectionCode.SELECT_INTO)),
                Arguments.of("SELECT 1; DROP TABLE orders", Set.of(RejectionCode.MULTIPLE_STATEMENTS)),
                Arguments.of("SELECT 1; ＤＲＯＰ ＴＡＢＬＥ orders", Set.of(RejectionCode.MULTIPLE_STATEMENTS)),
                Arguments.of("SELECT 1； ＤＲＯＰ ＴＡＢＬＥ orders", Set.of(RejectionCode.MULTIPLE_STATEMENTS)),
                Arguments.of("COPY (SELECT * FROM orders) TO '/tmp/x'",
                        Set.of(RejectionCode.NON_SELECT_STATEMENT, RejectionCode.SQL_PARSE_ERROR)),
                Arguments.of("SELECT * FROM orders FOR UPDATE", Set.of(RejectionCode.LOCKING_CLAUSE)),
                // Other non-SELECT statements
                Arguments.of("UPDATE orders SET status = 'x'", Set.of(RejectionCode.NON_SELECT_STATEMENT)),
                Arguments.of("DELETE FROM orders", Set.of(RejectionCode.NON_SELECT_STATEMENT)),
                Arguments.of("INSERT INTO orders(id) VALUES (1)", Set.of(RejectionCode.NON_SELECT_STATEMENT)),
                Arguments.of("DROP TABLE orders", Set.of(RejectionCode.NON_SELECT_STATEMENT)),
                Arguments.of("CREATE TABLE t (id int)", Set.of(RejectionCode.NON_SELECT_STATEMENT)),
                Arguments.of("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET v = s.v",
                        Set.of(RejectionCode.NON_SELECT_STATEMENT, RejectionCode.SQL_PARSE_ERROR)),
                Arguments.of("CALL do_things()",
                        Set.of(RejectionCode.NON_SELECT_STATEMENT, RejectionCode.SQL_PARSE_ERROR)),
                Arguments.of("DO $$ BEGIN DELETE FROM orders; END $$",
                        Set.of(RejectionCode.NON_SELECT_STATEMENT, RejectionCode.SQL_PARSE_ERROR,
                                RejectionCode.MULTIPLE_STATEMENTS)),
                Arguments.of("EXPLAIN ANALYZE SELECT 1",
                        Set.of(RejectionCode.NON_SELECT_STATEMENT, RejectionCode.SQL_PARSE_ERROR)),
                Arguments.of("WITH d AS (SELECT 1) DELETE FROM orders",
                        Set.of(RejectionCode.NON_SELECT_STATEMENT)),
                // Hidden deep in the tree
                Arguments.of("WITH a AS (SELECT 1), b AS (DELETE FROM orders RETURNING id) SELECT * FROM a",
                        Set.of(RejectionCode.DATA_MODIFYING_CTE)),
                Arguments.of("WITH a AS (UPDATE orders SET v = 1 RETURNING id) SELECT * FROM a",
                        Set.of(RejectionCode.DATA_MODIFYING_CTE)),
                Arguments.of("SELECT * FROM orders FOR SHARE", Set.of(RejectionCode.LOCKING_CLAUSE)),
                Arguments.of("SELECT * FROM orders WHERE id IN (SELECT id FROM orders FOR UPDATE)",
                        Set.of(RejectionCode.LOCKING_CLAUSE)),
                Arguments.of("SELECT * FROM (SELECT * FROM orders FOR UPDATE) o",
                        Set.of(RejectionCode.LOCKING_CLAUSE)),
                Arguments.of("SELECT 1 UNION ALL SELECT * INTO t FROM orders",
                        Set.of(RejectionCode.SELECT_INTO)),
                Arguments.of("WITH a AS (SELECT * FROM orders FOR UPDATE) SELECT * FROM a",
                        Set.of(RejectionCode.LOCKING_CLAUSE)),
                // Parse failures
                Arguments.of("", Set.of(RejectionCode.SQL_PARSE_ERROR)),
                Arguments.of("   ", Set.of(RejectionCode.SQL_PARSE_ERROR)),
                Arguments.of("SELEC 1 FROM", Set.of(RejectionCode.SQL_PARSE_ERROR)),
                Arguments.of("I'm sorry, I can't help with that.", Set.of(RejectionCode.SQL_PARSE_ERROR)));
    }

    @ParameterizedTest
    @MethodSource("rejectedFixtures")
    void rejectsWithExpectedCode(String sql, Set<RejectionCode> allowed) {
        ValidationResult result = validator.validate(sql);
        assertThat(result.accepted()).as(sql).isFalse();
        assertThat(result.rejections()).as(sql).isNotEmpty();
        assertThat(result.rejections()).as(sql).allSatisfy(r -> {
            assertThat(allowed).contains(r.code());
            assertThat(r.reason()).isNotBlank();
        });
    }

    @Test
    void fullwidthSelectIsAcceptedAndNormalized() {
        ValidationResult result = validator.validate("ＳＥＬＥＣＴ １");
        assertThat(result.accepted()).isTrue();
        assertThat(result.normalizedSql()).isEqualTo("SELECT 1");

        ValidationResult letters = validator.validate("ＳＥＬＥＣＴ 1");
        assertThat(letters.accepted()).isTrue();
        assertThat(letters.normalizedSql()).isEqualTo("SELECT 1");
    }

    @Test
    void stackedStatementReturnsNormalizedText() {
        ValidationResult result = validator.validate("SELECT 1; ＤＲＯＰ ＴＡＢＬＥ orders");
        assertThat(result.normalizedSql()).isEqualTo("SELECT 1; DROP TABLE orders");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "```sql\nSELECT 1\n```",
            "Here is your query:\n```sql\nSELECT 1\n```\nHope it helps.",
            "```\nSELECT 1\n```",
            "  SELECT 1  \n"
    })
    void stripsFencesAndProse(String raw) {
        ValidationResult result = validator.validate(raw);
        assertThat(result.accepted()).isTrue();
        assertThat(result.normalizedSql()).isEqualTo("SELECT 1");
    }

    @Test
    void fencedWriteIsStillRejected() {
        ValidationResult result = validator.validate("```sql\nDELETE FROM orders\n```");
        assertThat(result.accepted()).isFalse();
        assertThat(result.normalizedSql()).isEqualTo("DELETE FROM orders");
        assertThat(result.rejections()).extracting(Rejection::code).containsExactly(RejectionCode.NON_SELECT_STATEMENT);
    }

    @Test
    void nullInputIsParseError() {
        ValidationResult result = validator.validate(null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.normalizedSql()).isEmpty();
        assertThat(result.rejections()).extracting(Rejection::code).containsExactly(RejectionCode.SQL_PARSE_ERROR);
    }
}
