package com.devassisthub.commit.service;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** Builds the unified diff between two snapshots, one chunk per changed file (added, modified or deleted). */
@Component
public class DiffBuilder {

    private static final int CONTEXT_LINES = 3;

    /** The diff chunk of one changed file. */
    public record FileDiff(String path, String text) {
    }

    /** {@code before} is empty for the first upload of a project, so every file is an addition. */
    public List<FileDiff> build(Map<String, String> before, Map<String, String> after) {
        TreeSet<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<FileDiff> diffs = new ArrayList<>();
        for (String path : paths) {
            String oldText = before.get(path);
            String newText = after.get(path);
            if (oldText != null && oldText.equals(newText)) {
                continue;
            }
            List<String> oldLines = lines(oldText);
            List<String> newLines = lines(newText);
            Patch<String> patch = DiffUtils.diff(oldLines, newLines);
            List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                    oldText == null ? "/dev/null" : "a/" + path,
                    newText == null ? "/dev/null" : "b/" + path,
                    oldLines, patch, CONTEXT_LINES);
            if (unified.isEmpty()) {
                continue;
            }
            String body = String.join("\n", unified)
                    // The library writes an empty side as "-1,0" / "+1,0"; standard unified diffs use 0,0.
                    .replaceAll("(?m)^@@ -1,0 ", "@@ -0,0 ")
                    .replaceAll("(?m)^(@@ -\\S+ )\\+1,0 @@", "$1+0,0 @@");
            diffs.add(new FileDiff(path, "diff --git a/" + path + " b/" + path + "\n" + body));
        }
        return diffs;
    }

    public static String join(List<FileDiff> diffs) {
        return String.join("\n", diffs.stream().map(FileDiff::text).toList());
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return Arrays.asList(body.split("\n", -1));
    }
}
