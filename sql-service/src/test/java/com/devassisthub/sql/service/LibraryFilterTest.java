package com.devassisthub.sql.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devassisthub.sql.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LibraryFilterTest {

    @Test
    void allNullOrBlankMeansNoFilter() {
        assertThat(LibraryFilter.of(null, null, null, null, null)).isEqualTo(LibraryFilter.NONE);
        assertThat(LibraryFilter.of(" ", " , ,", "", " ", " ")).isEqualTo(LibraryFilter.NONE);
    }

    @Test
    void parsesAndTrimsValues() {
        LibraryFilter f = LibraryFilter.of(" ORDERS ", "reporting, finance,reporting", " alice ", "False", "TRUE");
        assertThat(f).isEqualTo(new LibraryFilter("ORDERS", List.of("reporting", "finance"), "alice", false, true));
        assertThat(LibraryFilter.of(null, null, null, "true", null).llmGenerated()).isTrue();
    }

    @Test
    void boundaryLengthsAccepted() {
        LibraryFilter f = LibraryFilter.of("q".repeat(200), "t".repeat(50) + ",a,b,c,d,e,f,g,h,i", "a".repeat(100), null, null);
        assertThat(f.tags()).hasSize(10);
    }

    @ParameterizedTest
    @CsvSource({"llm_generated", "deprecated", "q", "tags", "author", "tag_length"})
    void invalidValuesAre400NamingTheField(String which) {
        String q = which.equals("q") ? "x".repeat(201) : null;
        String tags = switch (which) {
            case "tags" -> "a,b,c,d,e,f,g,h,i,j,k";
            case "tag_length" -> "t".repeat(51);
            default -> null;
        };
        String author = which.equals("author") ? "a".repeat(101) : null;
        String llm = which.equals("llm_generated") ? "yes" : null;
        String dep = which.equals("deprecated") ? "maybe" : null;
        String field = which.equals("tag_length") ? "tags" : which;
        assertThatThrownBy(() -> LibraryFilter.of(q, tags, author, llm, dep))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(400);
                    assertThat(e.getField()).isEqualTo(field);
                });
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(LibraryFilter.escapeLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
    }
}
