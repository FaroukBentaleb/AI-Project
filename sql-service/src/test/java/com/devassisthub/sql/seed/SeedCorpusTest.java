package com.devassisthub.sql.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devassisthub.sql.validation.FunctionPolicy;
import com.devassisthub.sql.validation.ReadOnlyValidator;
import com.devassisthub.sql.validation.ValidationResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Story 5.5: the bundled corpus is complete, validator-clean and covers the idioms named in addendum §F. */
class SeedCorpusTest {

    private static final String LAST_WEEK = "created_at >= date_trunc('week', now()) - interval '1 week'\n"
            + "  AND o.created_at < date_trunc('week', now())";

    private final List<SeedEntry> corpus = SeedCorpus.load();

    @Test
    void hasTenToTwentyCompleteEntriesWithUniqueKeys() {
        assertThat(corpus).hasSizeBetween(10, 20);
        assertThat(corpus).extracting(SeedEntry::seedKey).doesNotHaveDuplicates();
        assertThat(corpus).allSatisfy(e -> {
            assertThat(e.title()).isNotBlank().hasSizeLessThanOrEqualTo(200);
            assertThat(e.description()).isNotBlank().hasSizeLessThanOrEqualTo(2000);
            assertThat(e.tags()).isNotEmpty().hasSizeLessThanOrEqualTo(10)
                    .allSatisfy(t -> assertThat(t).isNotBlank().hasSizeLessThanOrEqualTo(50));
        });
    }

    @Test
    void everySeedPassesTheReadOnlyValidatorWithoutWarnings() {
        ReadOnlyValidator validator = new ReadOnlyValidator(FunctionPolicy.defaults());
        assertThat(corpus).allSatisfy(e -> {
            ValidationResult r = validator.validate(e.sqlContent());
            assertThat(r.accepted()).as(e.seedKey() + ": " + r.rejections()).isTrue();
            assertThat(r.warnings()).as(e.seedKey()).isEmpty();
        });
    }

    @Test
    void coversTheIdiomsOfAddendumF() {
        List<String> sql = corpus.stream().map(SeedEntry::sqlContent).toList();
        assertThat(sql).anyMatch(s -> s.contains("UNION ALL"));
        assertThat(sql).anyMatch(s -> s.startsWith("WITH") && s.contains("previous_week"));
        assertThat(sql).anyMatch(s -> s.contains(" OVER ("));
        assertThat(sql).anyMatch(s -> s.contains("FILTER (WHERE"));
        assertThat(sql).anyMatch(s -> s.contains("DISTINCT ON"));
        // One "last week" idiom: every query filtering on the week boundary uses exactly this text.
        assertThat(sql).filteredOn(s -> s.contains("date_trunc('week'"))
                .hasSizeGreaterThanOrEqualTo(5)
                .allMatch(s -> s.contains(LAST_WEEK));
        assertThat(corpus).anyMatch(e -> e.seedKey().equals("weekly_region_orders")
                && e.title().equals("Weekly orders by region"));
    }

    @Test
    void rejectsIncompleteOrDuplicateEntries() {
        SeedEntry ok = new SeedEntry("k", "t", "d", "SELECT 1", List.of("x"));
        assertThatThrownBy(() -> SeedCorpus.check(List.of(ok, ok))).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> SeedCorpus.check(List.of(new SeedEntry("k", "t", " ", "SELECT 1", List.of("x")))))
                .hasMessageContaining("incomplete");
        assertThatThrownBy(() -> SeedCorpus.check(List.of(new SeedEntry("k", "t", "d", "SELECT 1", List.of()))))
                .hasMessageContaining("incomplete");
    }
}
