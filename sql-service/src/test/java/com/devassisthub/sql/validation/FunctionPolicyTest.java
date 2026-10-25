package com.devassisthub.sql.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class FunctionPolicyTest {

    private final ReadOnlyValidator validator = new ReadOnlyValidator();

    static Stream<Arguments> blockedCalls() {
        return Stream.of(
                // FR-11 fixtures
                Arguments.of("SELECT pg_read_file('/etc/passwd')", "pg_read_file"),
                Arguments.of("SELECT dblink('host=evil', 'DROP TABLE orders')", "dblink"),
                // every FR-10 family
                Arguments.of("SELECT pg_read_binary_file('/etc/passwd')", "pg_read_binary_file"),
                Arguments.of("SELECT * FROM pg_ls_dir('/') LIMIT 5", "pg_ls_dir"),
                Arguments.of("SELECT pg_stat_file('/etc/passwd')", "pg_stat_file"),
                Arguments.of("SELECT lo_import('/etc/passwd')", "lo_import"),
                Arguments.of("SELECT lo_export(1, '/tmp/x')", "lo_export"),
                Arguments.of("SELECT lo_unlink(1)", "lo_unlink"),
                Arguments.of("SELECT lo_from_bytea(0, 'x')", "lo_from_bytea"),
                Arguments.of("SELECT dblink_exec('host=evil', 'DROP TABLE orders')", "dblink_exec"),
                Arguments.of("SELECT dblink_connect('host=evil')", "dblink_connect"),
                Arguments.of("SELECT * FROM dblink('host=evil', 'SELECT 1') AS t(a int) LIMIT 1", "dblink"),
                Arguments.of("SELECT pg_terminate_backend(123)", "pg_terminate_backend"),
                Arguments.of("SELECT pg_cancel_backend(123)", "pg_cancel_backend"),
                Arguments.of("SELECT pg_reload_conf()", "pg_reload_conf"),
                Arguments.of("SELECT set_config('search_path', 'evil', false)", "set_config"),
                Arguments.of("SELECT nextval('orders_id_seq')", "nextval"),
                Arguments.of("SELECT setval('orders_id_seq', 1)", "setval"),
                Arguments.of("SELECT pg_advisory_lock(1)", "pg_advisory_lock"),
                Arguments.of("SELECT pg_advisory_lock_shared(1)", "pg_advisory_lock_shared"),
                Arguments.of("SELECT query_to_xml('DELETE FROM orders', true, true, '')", "query_to_xml"),
                Arguments.of("SELECT query_to_xml_and_xmlschema('SELECT 1', true, true, '')",
                        "query_to_xml_and_xmlschema"),
                // schema-qualified, case and quoted variants
                Arguments.of("SELECT pg_catalog.pg_read_file('/etc/passwd')", "pg_read_file"),
                Arguments.of("SELECT PG_READ_FILE('/etc/passwd')", "pg_read_file"),
                Arguments.of("SELECT Pg_Read_File('/etc/passwd')", "pg_read_file"),
                Arguments.of("SELECT \"pg_read_file\"('/etc/passwd')", "pg_read_file"),
                Arguments.of("SELECT pg_catalog.\"pg_read_file\"('/etc/passwd')", "pg_read_file"),
                Arguments.of("SELECT public.DBLINK('x', 'y')", "dblink"),
                Arguments.of("SELECT ｐｇ＿ｒｅａｄ＿ｆｉｌｅ('/etc/passwd')", "pg_read_file"),
                // hidden deep in the tree
                Arguments.of("SELECT id FROM orders WHERE id IN (SELECT nextval('s'))", "nextval"),
                Arguments.of("WITH x AS (SELECT pg_read_file('/etc/passwd') AS f) SELECT f FROM x", "pg_read_file"),
                Arguments.of("SELECT 1 UNION ALL SELECT length(pg_read_file('/etc/passwd'))", "pg_read_file"),
                Arguments.of("SELECT o.id FROM orders o JOIN customers c ON c.id = o.customer_id"
                        + " AND set_config('a', 'b', false) IS NOT NULL", "set_config"),
                Arguments.of("SELECT CASE WHEN true THEN lo_unlink(1) END", "lo_unlink"));
    }

    @ParameterizedTest
    @MethodSource("blockedCalls")
    void rejectsBlockedFunctionNamingIt(String sql, String name) {
        ValidationResult result = validator.validate(sql);
        assertThat(result.accepted()).as(sql).isFalse();
        assertThat(result.warnings()).as(sql).isEmpty();
        assertThat(result.rejections()).as(sql).anySatisfy(r -> {
            assertThat(r.code()).isEqualTo(RejectionCode.BLOCKED_FUNCTION);
            assertThat(r.reason()).contains(name);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 'pg_read_file(1)' AS note",
            "SELECT pg_read_file_size FROM settings_report LIMIT 1",
            "SELECT lower(name), coalesce(email, '') FROM customers LIMIT 10",
            "SELECT currval_report FROM t LIMIT 1"
    })
    void lookalikesAreNotBlocked(String sql) {
        ValidationResult result = validator.validate(sql);
        assertThat(result.rejections()).as(sql).isEmpty();
        assertThat(result.accepted()).isTrue();
    }

    static Stream<Arguments> warnings() {
        return Stream.of(
                Arguments.of("SELECT pg_sleep(60)", ReadOnlyValidator.WARN_PG_SLEEP),
                Arguments.of("SELECT pg_catalog.PG_SLEEP(1)", ReadOnlyValidator.WARN_PG_SLEEP),
                Arguments.of("SELECT id FROM orders WHERE pg_sleep_for('1 minute') IS NULL LIMIT 1",
                        ReadOnlyValidator.WARN_PG_SLEEP),
                Arguments.of("SELECT o.id FROM orders o CROSS JOIN customers c", ReadOnlyValidator.WARN_CROSS_JOIN),
                Arguments.of("SELECT o.id FROM orders o, customers c", ReadOnlyValidator.WARN_COMMA_JOIN),
                Arguments.of("SELECT orders.id FROM orders, customers", ReadOnlyValidator.WARN_COMMA_JOIN),
                Arguments.of("SELECT o.id FROM orders o, customers c WHERE o.total > 10",
                        ReadOnlyValidator.WARN_COMMA_JOIN),
                Arguments.of("SELECT o.id FROM orders o, customers c WHERE o.id = o.parent_id",
                        ReadOnlyValidator.WARN_COMMA_JOIN),
                Arguments.of("SELECT n FROM (SELECT o.id AS n FROM orders o, customers c) s LIMIT 5",
                        ReadOnlyValidator.WARN_COMMA_JOIN),
                Arguments.of("SELECT * FROM orders", ReadOnlyValidator.WARN_SELECT_STAR),
                Arguments.of("SELECT * FROM orders WHERE status = 'paid' ORDER BY id",
                        ReadOnlyValidator.WARN_SELECT_STAR),
                Arguments.of("WITH x AS (SELECT id FROM orders) SELECT * FROM x", ReadOnlyValidator.WARN_SELECT_STAR));
    }

    @ParameterizedTest
    @MethodSource("warnings")
    void acceptsWithWarning(String sql, String warning) {
        ValidationResult result = validator.validate(sql);
        assertThat(result.rejections()).as(sql).isEmpty();
        assertThat(result.accepted()).isTrue();
        assertThat(result.warnings()).as(sql).containsExactly(warning);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT o.id FROM orders o, customers c WHERE o.customer_id = c.id",
            "SELECT orders.id FROM orders, customers WHERE customers.id = orders.customer_id AND orders.total > 5",
            "SELECT o.id FROM orders o JOIN customers c ON o.customer_id = c.id",
            "SELECT region, count(*) FROM orders GROUP BY region",
            "SELECT region, count(*) AS n, sum(total) FROM orders o JOIN customers c ON c.id = o.customer_id"
                    + " WHERE o.created_at >= now() - interval '30 days' GROUP BY region ORDER BY n DESC",
            "SELECT * FROM orders LIMIT 10",
            "SELECT * FROM orders FETCH FIRST 10 ROWS ONLY",
            "SELECT *, count(*) OVER () FROM orders LIMIT 5",
            "SELECT count(*) FROM orders",
            "SELECT o.* FROM orders o LIMIT 5",
            "SELECT id FROM (SELECT * FROM orders) s WHERE id > 3"
    })
    void ordinaryQueriesHaveNoWarning(String sql) {
        ValidationResult result = validator.validate(sql);
        assertThat(result.accepted()).as(sql).isTrue();
        assertThat(result.warnings()).as(sql).isEmpty();
    }

    @Test
    void blocklistIsConfigurable() {
        ReadOnlyValidator custom = new ReadOnlyValidator(new FunctionPolicy(List.of("  MY_DANGER ", "evil_*")));
        assertThat(custom.validate("SELECT my_danger()").rejections())
                .extracting(Rejection::code).containsExactly(RejectionCode.BLOCKED_FUNCTION);
        assertThat(custom.validate("SELECT s.EVIL_thing()").accepted()).isFalse();
        assertThat(custom.validate("SELECT pg_read_file('/etc/passwd')").accepted()).isTrue();
    }

    @Test
    void reportsEachBlockedFunctionOnce() {
        ValidationResult result = validator.validate("SELECT pg_read_file('a'), pg_read_file('b'), lo_import('c')");
        assertThat(result.rejections()).extracting(Rejection::reason)
                .containsExactly("pg_read_file is not allowed", "lo_import is not allowed");
    }
}
