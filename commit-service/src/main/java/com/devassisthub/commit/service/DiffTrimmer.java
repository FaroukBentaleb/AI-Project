package com.devassisthub.commit.service;

import com.devassisthub.commit.config.ModelProperties;
import com.devassisthub.commit.service.DiffBuilder.FileDiff;
import java.util.List;
import org.springframework.stereotype.Component;

/** Cuts the diff to the model's input budget: each file first, then the whole text, always on a line boundary. */
@Component
public class DiffTrimmer {

    static final String CUT_MARKER = "\n... [diff truncated]";
    /** A partial file chunk is kept only if at least this many chars of room are left. */
    private static final int MIN_PARTIAL_CHARS = 200;

    /** The text to send and whether anything was cut. */
    public record Trimmed(String text, boolean truncated) {
    }

    private final int maxChars;
    private final int maxCharsPerFile;

    public DiffTrimmer(ModelProperties props) {
        this.maxChars = props.maxDiffChars();
        this.maxCharsPerFile = props.maxDiffCharsPerFile();
    }

    public Trimmed trim(List<FileDiff> diffs) {
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        for (int i = 0; i < diffs.size(); i++) {
            String chunk = diffs.get(i).text();
            if (chunk.length() > maxCharsPerFile) {
                chunk = cut(chunk, maxCharsPerFile);
                truncated = true;
            }
            int separator = out.isEmpty() ? 0 : 1;
            int remaining = maxChars - out.length() - separator;
            if (chunk.length() > remaining) {
                boolean partial = remaining > MIN_PARTIAL_CHARS;
                if (partial) {
                    out.append(out.isEmpty() ? "" : "\n").append(cut(chunk, remaining));
                }
                int omitted = diffs.size() - i - (partial ? 1 : 0);
                if (omitted > 0) {
                    out.append("\n... [").append(omitted).append(omitted == 1 ? " more file" : " more files")
                            .append(" omitted]");
                }
                return new Trimmed(out.toString(), true);
            }
            out.append(out.isEmpty() ? "" : "\n").append(chunk);
        }
        return new Trimmed(out.toString(), truncated);
    }

    /** At most {@code limit} chars including the marker, ending at a line boundary where possible. */
    private static String cut(String text, int limit) {
        int budget = Math.max(0, limit - CUT_MARKER.length());
        String head = text.substring(0, Math.min(budget, text.length()));
        int newline = head.lastIndexOf('\n');
        if (newline > 0) {
            head = head.substring(0, newline);
        }
        return head + CUT_MARKER;
    }
}
