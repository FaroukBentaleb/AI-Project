package com.devassisthub.sql.service;

import com.devassisthub.sql.entity.SavedQuery;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Story 8.5 (FR35): renders Saved Queries as a plain-text {@code .sql} script. Every metadata value is a
 * single {@code --} line comment, so line breaks are collapsed (a value can never start a new SQL line) and
 * {@code /*} / {@code *}{@code /} are neutralised so no tool can read a block comment out of a header.
 * Each statement ends with a {@code ;} on its own line, which a trailing {@code --} comment in the SQL
 * cannot swallow. Text only: nothing here (or anywhere in the service) executes the SQL.
 */
public final class SqlExportWriter {

    /** CR, LF, NEL, LS, PS, VT, FF and other control characters. */
    private static final Pattern BREAKS = Pattern.compile("[\\r\\n\\x{85}\\x{2028}\\x{2029}\\p{Cntrl}]+");

    private SqlExportWriter() {
    }

    public static String write(List<SavedQuery> queries) {
        StringBuilder out = new StringBuilder();
        out.append("-- DevAssist Hub SQL library export\n");
        out.append("-- ").append(queries.size()).append(queries.size() == 1 ? " query" : " queries")
                .append(". Review before running; this file was not executed by the service.\n");
        for (SavedQuery q : queries) {
            out.append('\n');
            line(out, "Title", q.getTitle());
            line(out, "Description", q.getDescription());
            line(out, "Author", q.getAuthor());
            line(out, "Tags", q.getTags() == null || q.getTags().isEmpty() ? "" : String.join(", ", q.getTags()));
            line(out, "Read-only", readOnly(q));
            if (q.isDeprecated()) {
                String note = q.getDeprecatedReason() == null ? "" : q.getDeprecatedReason();
                if (q.getReplacedBy() != null) {
                    note = note + (note.isBlank() ? "" : " ") + "(replaced by #" + q.getReplacedBy() + ")";
                }
                line(out, "Deprecated", note.isBlank() ? "yes" : note);
            }
            out.append(statement(q.getSqlContent())).append('\n');
        }
        return out.toString();
    }

    static String readOnly(SavedQuery q) {
        Boolean r = q.getReadOnly();
        return r == null ? "unknown" : r ? "yes" : "no";
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append("-- ").append(label).append(": ").append(comment(value)).append('\n');
    }

    /** One-line, comment-safe rendering of a metadata value. */
    public static String comment(String value) {
        if (value == null) {
            return "";
        }
        String s = BREAKS.matcher(value).replaceAll(" ").strip();
        // Loop: "*/*/" style overlaps must not reassemble after one pass.
        String prev;
        do {
            prev = s;
            s = s.replace("*/", "* /").replace("/*", "/ *");
        } while (!s.equals(prev));
        return s;
    }

    /** SQL followed by a terminating {@code ;} on its own line (unless already terminated on a clean line). */
    static String statement(String sql) {
        String body = sql == null ? "" : sql.stripTrailing();
        int lastBreak = Math.max(body.lastIndexOf('\n'), body.lastIndexOf('\r'));
        String lastLine = body.substring(lastBreak + 1);
        boolean terminated = body.endsWith(";") && !lastLine.contains("--") && !lastLine.contains("/*");
        return terminated ? body : body + "\n;";
    }
}
