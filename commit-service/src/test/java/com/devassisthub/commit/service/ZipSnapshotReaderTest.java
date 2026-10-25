package com.devassisthub.commit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devassisthub.commit.config.UploadProperties;
import com.devassisthub.commit.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class ZipSnapshotReaderTest {

    private final ZipSnapshotReader reader = new ZipSnapshotReader(new UploadProperties(10, 1000, 5000));

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, String> read(Map<String, byte[]> entries) throws IOException {
        return reader.read(new ByteArrayInputStream(zip(entries)));
    }

    @Test
    void keepsTextFilesAndNormalizesLineEndings() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("index.html", text("<h1>Hi</h1>\r\n"));
        entries.put("css/site.css", text("body {}\n"));

        assertThat(read(entries)).containsOnly(
                Map.entry("index.html", "<h1>Hi</h1>\n"), Map.entry("css/site.css", "body {}\n"));
    }

    @Test
    void skipsBinaryIgnoredAndOversizedFiles() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("keep.txt", text("ok"));
        entries.put("logo.png", text("not really a png"));
        entries.put("data.bin", new byte[] {1, 0, 2});
        entries.put("node_modules/lib/index.js", text("x"));
        entries.put(".git/config", text("x"));
        entries.put("big.txt", new byte[1001]);

        assertThat(read(entries)).containsOnlyKeys("keep.txt");
    }

    @Test
    void stripsASingleSharedTopLevelFolder() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("site/index.html", text("a"));
        entries.put("site/js/app.js", text("b"));

        assertThat(read(entries)).containsOnlyKeys("index.html", "js/app.js");
    }

    @Test
    void keepsFoldersWhenThereAreSeveralRoots() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("src/a.txt", text("a"));
        entries.put("docs/b.txt", text("b"));

        assertThat(read(entries)).containsOnlyKeys("src/a.txt", "docs/b.txt");
    }

    @Test
    void rejectsPathTraversalEntries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("../evil.txt", text("x"));
        entries.put("ok.txt", text("y"));

        assertThat(read(entries)).containsOnlyKeys("ok.txt");
    }

    @Test
    void rejectsZipWithNoTextFiles() throws IOException {
        Map<String, byte[]> entries = Map.of("logo.png", text("x"));

        assertThatThrownBy(() -> read(entries)).isInstanceOf(ApiException.class)
                .hasMessageContaining("no text files");
    }

    @Test
    void rejectsTooManyFiles() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) {
            entries.put("f" + i + ".txt", text("x"));
        }

        assertThatThrownBy(() -> read(entries)).isInstanceOf(ApiException.class)
                .hasMessageContaining("more than 10");
    }

    @Test
    void rejectsExtractedSizeAboveTotalLimit() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            entries.put("f" + i + ".txt", text("x".repeat(1000)));
        }

        assertThatThrownBy(() -> read(entries)).isInstanceOf(ApiException.class)
                .hasMessageContaining("allowed size");
    }

    @Test
    void rejectsANonZipFile() {
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(text("this is not a zip"))))
                .isInstanceOf(ApiException.class);
    }
}
