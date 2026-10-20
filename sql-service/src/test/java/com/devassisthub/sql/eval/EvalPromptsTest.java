package com.devassisthub.sql.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.eval.EvalPrompts.EvalPrompt;
import com.devassisthub.sql.seed.SeedCorpus;
import com.devassisthub.sql.seed.SeedEntry;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Story 6.6 / SM-C2: 15 held-out prompts, none restating a seed query. */
class EvalPromptsTest {

    private static final Set<String> STOP = Set.of("the", "a", "an", "of", "per", "by", "and", "in", "for", "each",
            "with", "their", "who", "have", "has", "is", "to", "from", "on", "at", "last", "over", "all", "than");

    @Test
    void fifteenUniquePrompts() {
        List<EvalPrompt> prompts = EvalPrompts.load();
        assertThat(prompts).hasSize(15);
        assertThat(prompts.stream().map(EvalPrompt::id).distinct()).hasSize(15);
        assertThat(prompts).allSatisfy(p -> assertThat(p.prompt()).isNotBlank().hasSizeLessThanOrEqualTo(1000));
    }

    @Test
    void noPromptParaphrasesASeedTitle() {
        for (EvalPrompt p : EvalPrompts.load()) {
            Set<String> pw = words(p.prompt());
            for (SeedEntry s : SeedCorpus.load()) {
                assertThat(p.prompt().toLowerCase(Locale.ROOT)).doesNotContain(s.title().toLowerCase(Locale.ROOT));
                Set<String> sw = words(s.title());
                Set<String> common = new HashSet<>(sw);
                common.retainAll(pw);
                assertThat(common.size() / (double) sw.size())
                        .as("%s vs seed %s overlap %s", p.id(), s.seedKey(), common)
                        .isLessThan(0.75);
            }
        }
    }

    private static Set<String> words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> !w.isBlank() && !STOP.contains(w))
                .map(w -> w.endsWith("s") ? w.substring(0, w.length() - 1) : w)
                .collect(Collectors.toSet());
    }
}
