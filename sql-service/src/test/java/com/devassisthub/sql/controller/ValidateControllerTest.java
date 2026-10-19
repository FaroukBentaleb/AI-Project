package com.devassisthub.sql.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.config.ValidatorConfig;
import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.validation.SafetyNote;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** FR-11 fixture table and parser-coverage set through {@code POST /api/sql/validate}, plus request validation. */
@WebMvcTest(controllers = ValidateController.class)
@Import({GlobalExceptionHandler.class, ValidatorConfig.class})
class ValidateControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

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

    /** FR-11 rejected fixtures; the PRD allows SQL_PARSE_ERROR as an alternative outcome for two of them. */
    static Stream<Arguments> rejectedFixtures() {
        return Stream.of(
                Arguments.of("WITH x AS (INSERT INTO audit(id) VALUES (1) RETURNING id) SELECT 1",
                        List.of("DATA_MODIFYING_CTE", "SQL_PARSE_ERROR")),
                Arguments.of("SELECT * INTO t FROM orders", List.of("SELECT_INTO")),
                Arguments.of("SELECT 1; DROP TABLE orders", List.of("MULTIPLE_STATEMENTS")),
                Arguments.of("SELECT 1; ＤＲＯＰ ＴＡＢＬＥ orders", List.of("MULTIPLE_STATEMENTS")),
                Arguments.of("SELECT pg_read_file('/etc/passwd')", List.of("BLOCKED_FUNCTION")),
                Arguments.of("SELECT dblink('host=evil', 'DROP TABLE orders')", List.of("BLOCKED_FUNCTION")),
                Arguments.of("COPY (SELECT * FROM orders) TO '/tmp/x'",
                        List.of("NON_SELECT_STATEMENT", "SQL_PARSE_ERROR")),
                Arguments.of("SELECT * FROM orders FOR UPDATE", List.of("LOCKING_CLAUSE")));
    }

    @ParameterizedTest
    @MethodSource("rejectedFixtures")
    void fr11RejectedFixtures(String sql, List<String> allowedCodes) throws Exception {
        validate(sql).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejections", not(empty())))
                .andExpect(jsonPath("$.rejections[*].code", everyItem(in(allowedCodes))))
                .andExpect(jsonPath("$.rejections[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.warnings", empty()))
                .andExpect(jsonPath("$.safety_note").value(SafetyNote.TEXT));
    }

    @Test
    void blockedFunctionReasonNamesTheFunction() throws Exception {
        validate("SELECT pg_read_file('/etc/passwd')").andExpect(status().isOk())
                .andExpect(jsonPath("$.rejections[0].code").value("BLOCKED_FUNCTION"))
                .andExpect(jsonPath("$.rejections[0].reason").value("pg_read_file is not allowed"));
    }

    @Test
    void fullwidthSelectIsAcceptedAndNormalized() throws Exception {
        validate("ＳＥＬＥＣＴ 1").andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.normalized_sql").value("SELECT 1"))
                .andExpect(jsonPath("$.rejections", empty()))
                .andExpect(jsonPath("$.warnings", empty()))
                .andExpect(jsonPath("$.safety_note").value(SafetyNote.TEXT));
    }

    @Test
    void pgSleepIsAcceptedWithWarning() throws Exception {
        validate("SELECT pg_sleep(60)").andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.rejections", empty()))
                .andExpect(jsonPath("$.warnings", hasSize(1)));
    }

    /** Week-over-week (FR-11) and the parser-coverage set (SM-1, SM-C1): accepted, no rejection, no Warning. */
    @ParameterizedTest
    @ValueSource(strings = {
            WOW_UNION,
            WOW_CTE,
            "SELECT id, rank() OVER (PARTITION BY region ORDER BY total DESC) AS r FROM orders",
            "SELECT id, sum(total) OVER (ORDER BY created_at ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) FROM orders",
            "SELECT customer_id, row_number() OVER w FROM orders WINDOW w AS (PARTITION BY customer_id ORDER BY created_at)",
            "SELECT id, lag(total) OVER (PARTITION BY customer_id ORDER BY created_at) AS prev FROM orders",
            "SELECT region, count(*) FILTER (WHERE status = 'paid') AS paid FROM orders GROUP BY region",
            "SELECT count(*) FILTER (WHERE total > 100) AS big, sum(total) FILTER (WHERE status = 'refunded') FROM orders",
            "SELECT DISTINCT ON (customer_id) customer_id, total FROM orders ORDER BY customer_id, created_at DESC",
            "SELECT id FROM orders WHERE created_at >= now() - interval '30 days'",
            "SELECT date_trunc('month', created_at) + INTERVAL '1 month' - INTERVAL '1 day' AS month_end, count(*) FROM orders GROUP BY 1",
            "SELECT id FROM orders WHERE created_at BETWEEN current_date - interval '7 days' AND current_date",
            "SELECT id, created_at::date AS day FROM orders WHERE created_at > now() - '1 week'::interval"
    })
    void parserCoverageAndWeekOverWeekAccepted(String sql) throws Exception {
        validate(sql).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.rejections", empty()))
                .andExpect(jsonPath("$.warnings", empty()))
                .andExpect(jsonPath("$.normalized_sql").value(sql));
    }

    @Test
    void responseHasExactlyTheAddendumShape() throws Exception {
        String body = validate("SELECT 1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> map = json.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(map.keySet())
                .containsExactlyInAnyOrder("accepted", "normalized_sql", "rejections", "warnings", "safety_note");
        String rejected = validate("SELECT 1; DROP TABLE orders").andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) ((List<?>) json.readValue(rejected, Map.class).get("rejections")).get(0);
        assertThat(first.keySet()).containsExactlyInAnyOrder("code", "reason");
        assertThat(first.get("code")).isEqualTo("MULTIPLE_STATEMENTS");
    }

    @Test
    void markdownFencedInputIsValidatedAsNormalizedText() throws Exception {
        validate("```sql\nSELECT 1\n```").andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.normalized_sql").value("SELECT 1"));
    }

    @Test
    void parseErrorIs200WithRejection() throws Exception {
        validate("SELEC 1 FROM").andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejections[*].code", hasItem("SQL_PARSE_ERROR")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"sql_content\":\"\"}", "{\"sql_content\":\"   \\n \"}", "{\"sql_content\":null}"})
    void blankSqlIs400(String body) throws Exception {
        send(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("sql_content"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]"})
    void emptyOrMalformedBodyIs400(String body) throws Exception {
        send(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void sqlOver20000CharsIs400AndExactly20000IsValidated() throws Exception {
        String base = "SELECT 1 -- ";
        String exact = base + "x".repeat(20000 - base.length());
        validate(exact).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        validate(exact + "x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("sql_content"));
    }

    @Test
    void getIs405() throws Exception {
        mvc.perform(get("/api/sql/validate")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    private ResultActions validate(String sql) throws Exception {
        return send(json.writeValueAsString(Map.of("sql_content", sql)));
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/sql/validate").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
