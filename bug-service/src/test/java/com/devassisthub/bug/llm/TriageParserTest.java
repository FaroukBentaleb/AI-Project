package com.devassisthub.bug.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.Severity;
import com.devassisthub.bug.llm.TriageParser.InvalidOutputException;
import com.devassisthub.bug.llm.TriageParser.Suggestion;
import org.junit.jupiter.api.Test;

class TriageParserTest {

    private final TriageParser parser = new TriageParser();

    @Test
    void parsesAPlainJsonAnswer() throws InvalidOutputException {
        Suggestion s = parser.parse("{\"severity\":\"HIGH\",\"category\":\"CRASH\","
                + "\"title\":\"App crashes when saving an empty cart\",\"rationale\":\"Checkout is blocked.\"}");

        assertThat(s.severity()).isEqualTo(Severity.HIGH);
        assertThat(s.category()).isEqualTo(BugCategory.CRASH);
        assertThat(s.title()).isEqualTo("App crashes when saving an empty cart");
        assertThat(s.rationale()).isEqualTo("Checkout is blocked.");
    }

    @Test
    void toleratesFencesThinkBlocksProseCaseAndSynonyms() throws InvalidOutputException {
        Suggestion s = parser.parse("<think>hmm, crash?</think>Sure! Here it is:\n```json\n{\"severity\": \"major\", "
                + "\"category\": \"ui/ux\", \"title\": \"Bug: \\\"Login button hidden on mobile.\\\"\"}\n```");

        assertThat(s.severity()).isEqualTo(Severity.HIGH);
        assertThat(s.category()).isEqualTo(BugCategory.UI);
        assertThat(s.title()).isEqualTo("Login button hidden on mobile");
        assertThat(s.rationale()).isNull();
    }

    @Test
    void acceptsSuggestedTitleAsKey() throws InvalidOutputException {
        Suggestion s = parser.parse("{\"severity\":\"low\",\"category\":\"other\",\"suggested_title\":\"Typo in footer text\"}");

        assertThat(s.title()).isEqualTo("Typo in footer text");
    }

    @Test
    void cutsLongTitlesAtAWord() throws InvalidOutputException {
        String longTitle = "word ".repeat(40).strip();

        Suggestion s = parser.parse("{\"severity\":\"LOW\",\"category\":\"UI\",\"title\":\"" + longTitle + "\"}");

        assertThat(s.title().length()).isLessThanOrEqualTo(TriageParser.MAX_TITLE);
        assertThat(s.title()).endsWith("word");
    }

    @Test
    void rejectsUnknownValuesWithAReason() {
        assertThatThrownBy(() -> parser.parse("{\"severity\":\"HUGE\",\"category\":\"UI\",\"title\":\"Some title here\"}"))
                .isInstanceOf(InvalidOutputException.class)
                .hasMessageContaining("severity \"HUGE\" is not one of LOW, MEDIUM, HIGH, CRITICAL");
        assertThatThrownBy(() -> parser.parse("{\"severity\":\"LOW\",\"category\":\"COSMIC\",\"title\":\"Some title here\"}"))
                .hasMessageContaining("category \"COSMIC\"");
    }

    @Test
    void rejectsMissingFieldsShortTitlesAndNonJson() {
        assertThatThrownBy(() -> parser.parse("{\"severity\":\"LOW\",\"title\":\"Some title here\"}"))
                .hasMessage("\"category\" was missing");
        assertThatThrownBy(() -> parser.parse("{\"severity\":\"LOW\",\"category\":\"UI\",\"title\":\"Bug\"}"))
                .hasMessageContaining("shorter than");
        assertThatThrownBy(() -> parser.parse("I think it is a high severity crash."))
                .hasMessage("the answer contained no JSON object");
        assertThatThrownBy(() -> parser.parse("{severity: HIGH,}")).hasMessage("the JSON object was malformed");
        assertThatThrownBy(() -> parser.parse("  ")).hasMessage("the answer was empty");
    }
}
