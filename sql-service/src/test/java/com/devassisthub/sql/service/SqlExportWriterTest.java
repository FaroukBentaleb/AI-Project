package com.devassisthub.sql.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.entity.SavedQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SqlExportWriterTest {

    static SavedQuery q(String title, String sql) {
        SavedQuery q = new SavedQuery();
        q.setTitle(title);
        q.setDescription("desc");
        q.setAuthor("alice");
        q.setSqlContent(sql);
        return q;
    }

    @Test
    void writesHeaderBlockThenTerminatedSql() {
        SavedQuery a = q("Active users", "SELECT * FROM users");
        a.setTags(List.of("users", "kpi"));
        ReflectionTestUtils.setField(a, "readOnly", true);
        String out = SqlExportWriter.write(List.of(a));
        assertThat(out).startsWith("-- DevAssist Hub SQL library export\n-- 1 query.");
        assertThat(out).contains("-- Title: Active users\n-- Description: desc\n-- Author: alice\n"
                + "-- Tags: users, kpi\n-- Read-only: yes\nSELECT * FROM users\n;\n");
        assertThat(out).doesNotContain("Deprecated:");
    }

    @Test
    void deprecatedNoteAndUnknownReadOnly() {
        SavedQuery a = q("Old", "SELECT 1;");
        ReflectionTestUtils.setField(a, "deprecated", true);
        ReflectionTestUtils.setField(a, "deprecatedReason", "slow");
        ReflectionTestUtils.setField(a, "replacedBy", 7L);
        String out = SqlExportWriter.write(List.of(a));
        assertThat(out).contains("-- Read-only: unknown\n-- Deprecated: slow (replaced by #7)\nSELECT 1;\n");
        ReflectionTestUtils.setField(a, "deprecatedReason", null);
        ReflectionTestUtils.setField(a, "replacedBy", null);
        assertThat(SqlExportWriter.write(List.of(a))).contains("-- Deprecated: yes\n");
    }

    @Test
    void newlinesInMetadataCannotStartSqlLines() {
        SavedQuery a = q("t\nDROP TABLE users;", "SELECT 1");
        a.setDescription("line1\r\nline2 line3\u0085x\u000Bv");
        a.setTags(List.of("a\nb"));
        String out = SqlExportWriter.write(List.of(a));
        assertThat(out).contains("-- Title: t DROP TABLE users;\n");
        assertThat(out).contains("-- Description: line1 line2 line3 x v\n");
        assertThat(out).contains("-- Tags: a b\n");
        for (String line : out.split("\n", -1)) {
            assertThat(line.startsWith("--") || line.isEmpty() || line.equals("SELECT 1") || line.equals(";"))
                    .as(line).isTrue();
        }
    }

    @Test
    void blockCommentDelimitersAreNeutralised() {
        assertThat(SqlExportWriter.comment("a */ DROP /* b")).isEqualTo("a * / DROP / * b");
        assertThat(SqlExportWriter.comment("**//*")).doesNotContain("*/").doesNotContain("/*");
        assertThat(SqlExportWriter.comment("/*/")).doesNotContain("*/").doesNotContain("/*");
        assertThat(SqlExportWriter.comment(null)).isEmpty();
    }

    @Test
    void trailingLineCommentCannotSwallowTerminator() {
        assertThat(SqlExportWriter.statement("SELECT 1 -- note;")).isEqualTo("SELECT 1 -- note;\n;");
        assertThat(SqlExportWriter.statement("SELECT 1;  \n")).isEqualTo("SELECT 1;");
        assertThat(SqlExportWriter.statement("SELECT 1 /* x */;")).isEqualTo("SELECT 1 /* x */;\n;");
        assertThat(SqlExportWriter.statement("SELECT 1")).isEqualTo("SELECT 1\n;");
    }

    @Test
    void emptyExportHasOnlyPreamble() {
        assertThat(SqlExportWriter.write(List.of())).contains("-- 0 queries.").doesNotContain("Title:");
    }
}
