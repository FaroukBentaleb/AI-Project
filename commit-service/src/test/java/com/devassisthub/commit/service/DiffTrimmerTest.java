package com.devassisthub.commit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.commit.config.ModelProperties;
import com.devassisthub.commit.service.DiffBuilder.FileDiff;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffTrimmerTest {

    private static DiffTrimmer trimmer(int maxChars, int perFile) {
        return new DiffTrimmer(new ModelProperties("http://x", Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                maxChars, perFile));
    }

    private static FileDiff file(String path, int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append(i == 0 ? "" : "\n").append("+line ").append(i).append(" of ").append(path);
        }
        return new FileDiff(path, sb.toString());
    }

    @Test
    void smallDiffIsUntouched() {
        DiffTrimmer.Trimmed t = trimmer(1000, 500).trim(List.of(file("a", 3), file("b", 3)));

        assertThat(t.truncated()).isFalse();
        assertThat(t.text()).isEqualTo(file("a", 3).text() + "\n" + file("b", 3).text());
    }

    @Test
    void longFileIsCutToThePerFileLimitOnALineBoundary() {
        DiffTrimmer.Trimmed t = trimmer(5000, 300).trim(List.of(file("a", 100)));

        assertThat(t.truncated()).isTrue();
        assertThat(t.text().length()).isLessThanOrEqualTo(300);
        assertThat(t.text()).endsWith(DiffTrimmer.CUT_MARKER);
        assertThat(t.text().substring(0, t.text().length() - DiffTrimmer.CUT_MARKER.length()))
                .doesNotEndWith("\n").matches("(?s).*\\+line \\d+ of a");
    }

    @Test
    void totalLimitOmitsLaterFilesAndSaysSo() {
        List<FileDiff> files = List.of(file("a", 12), file("b", 12), file("c", 12), file("d", 12));

        DiffTrimmer.Trimmed t = trimmer(600, 500).trim(files);

        assertThat(t.truncated()).isTrue();
        assertThat(t.text().length()).isLessThanOrEqualTo(600 + 40);
        assertThat(t.text()).contains("a").contains("more file");
        assertThat(t.text()).doesNotContain("of d");
    }
}
