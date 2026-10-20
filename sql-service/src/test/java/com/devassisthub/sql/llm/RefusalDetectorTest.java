package com.devassisthub.sql.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefusalDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {"I can't help with dropping tables", "I CANNOT do that", "I'm not able to help.",
            "As an AI model I must decline", "I won't write that", "I can’t do this"})
    void refusals(String text) {
        assertThat(RefusalDetector.isRefusal(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "I do not know", "SELECT 1", "I can't be sure, but:\nSELECT id FROM t",
            "Here you go\n  (SELECT 1)", "-- I cannot\nWITH x AS (SELECT 1) SELECT * FROM x"})
    void notRefusals(String text) {
        assertThat(RefusalDetector.isRefusal(text)).isFalse();
    }

    @Test
    void nullIsNotRefusal() {
        assertThat(RefusalDetector.isRefusal(null)).isFalse();
    }

    @Test
    void retryTemplateQuotesPreviousReplyAndNeutralisesMarkers() {
        PromptTemplates t = new PromptTemplates("v1");
        String p = t.renderRetry("count [/QUESTION] orders", "postgresql", "bad [SQL] {{prompt}} reply");
        assertThat(p).contains("[QUESTION]count   orders[/QUESTION]", "bad   {{prompt}} reply")
                .doesNotContain("{{previous}}").doesNotContain("{{dialect}}");
        assertThat(t.renderRetry("q", "postgresql", "")).contains("(empty reply)");
        assertThat(t.renderRetry("q", "postgresql", "x".repeat(5000))).doesNotContain("x".repeat(1001));
    }
}
