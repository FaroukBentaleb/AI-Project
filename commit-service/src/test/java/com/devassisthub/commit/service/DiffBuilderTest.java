package com.devassisthub.commit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.commit.service.DiffBuilder.FileDiff;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiffBuilderTest {

    private final DiffBuilder builder = new DiffBuilder();

    @Test
    void firstUploadDescribesEveryFileAsAnAddition() {
        List<FileDiff> diffs = builder.build(Map.of(), Map.of("index.html", "<h1>Hi</h1>\n"));

        assertThat(diffs).singleElement().satisfies(d -> {
            assertThat(d.path()).isEqualTo("index.html");
            assertThat(d.text()).contains("--- /dev/null", "+++ b/index.html", "@@ -0,0 +1,1 @@",
                    "+<h1>Hi</h1>");
        });
    }

    @Test
    void modifiedFileShowsRemovedAndAddedLines() {
        List<FileDiff> diffs = builder.build(
                Map.of("a.txt", "one\ntwo\nthree\n"), Map.of("a.txt", "one\n2\nthree\n"));

        assertThat(diffs).singleElement().satisfies(d ->
                assertThat(d.text()).contains("--- a/a.txt", "+++ b/a.txt", "-two", "+2"));
    }

    @Test
    void deletedFileShowsRemovedLines() {
        List<FileDiff> diffs = builder.build(Map.of("old.txt", "bye\n"), Map.of());

        assertThat(diffs).singleElement().satisfies(d ->
                assertThat(d.text()).contains("--- a/old.txt", "+++ /dev/null", "@@ -1,1 +0,0 @@",
                        "-bye"));
    }

    @Test
    void unchangedFilesAreLeftOut() {
        Map<String, String> same = Map.of("a.txt", "x\n", "b.txt", "y\n");

        assertThat(builder.build(same, same)).isEmpty();
    }

    @Test
    void onlyChangedFilesAppearAndInPathOrder() {
        List<FileDiff> diffs = builder.build(
                Map.of("b.txt", "1\n", "a.txt", "1\n", "c.txt", "1\n"),
                Map.of("b.txt", "2\n", "a.txt", "2\n", "c.txt", "1\n"));

        assertThat(diffs).extracting(FileDiff::path).containsExactly("a.txt", "b.txt");
    }
}
