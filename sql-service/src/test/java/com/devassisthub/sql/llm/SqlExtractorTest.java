package com.devassisthub.sql.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Story 6.1: completion variants -> extracted SQL. */
class SqlExtractorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "```sql\nSELECT id FROM orders;\n```",
            "Here you go:\n```\nSELECT id FROM orders;\n```\nHope it helps.",
            " SELECT id FROM orders;\n[/SQL]",
            "[SQL]\nSELECT id FROM orders;\n[/SQL]\n",
            "Here is the query:\nSELECT id FROM orders;\n\nThis query lists every order id.",
            "```sql\nSELECT id FROM orders;",
            "The answer is SELECT id FROM orders;",
            "SELECT id FROM orders;\n\nNote: add an index."
    })
    void extractsTheSelect(String raw) {
        assertThat(SqlExtractor.extract(raw)).contains("SELECT id FROM orders;");
    }

    @org.junit.jupiter.api.Test
    void keepsClauseAfterBlankLineButDropsProse() {
        assertThat(SqlExtractor.extract("SELECT id\n\nFROM orders\n\nThis lists the orders."))
                .contains("SELECT id\n\nFROM orders");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "I do not know", "Sorry, I cannot answer that."})
    void noSqlIsEmpty(String raw) {
        assertThat(SqlExtractor.extract(raw)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "DROP TABLE orders|DROP TABLE orders",
            "WITH t AS (SELECT 1) SELECT * FROM t|WITH t AS (SELECT 1) SELECT * FROM t"
    })
    void keepsAnyStatement(String raw, String expected) {
        assertThat(SqlExtractor.extract(raw)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 1;\n\nDROP TABLE orders", "SELECT 1;\n\n  delete from orders"})
    void keepsSmuggledSecondStatement(String raw) {
        assertThat(SqlExtractor.extract(raw).orElseThrow()).contains("\n\n");
    }
}
