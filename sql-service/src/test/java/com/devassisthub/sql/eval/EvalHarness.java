package com.devassisthub.sql.eval;

import com.devassisthub.sql.eval.EvalPrompts.EvalPrompt;
import com.devassisthub.sql.eval.SqlGenerator.Generation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Story 6.6 (SM-2, SM-3, addendum §H): every prompt x {RAG on, RAG off} x N runs. An attempt is executable when
 * generation returned SQL and that SQL ran in the read-only sandbox. Generation failures count as not executable.
 */
public final class EvalHarness {

    public record Attempt(String promptId, boolean rag, int run, String sql, String failure) {
        public boolean executable() {
            return failure == null;
        }
    }

    public record Report(String label, List<EvalPrompt> prompts, int runs, List<Attempt> attempts) {

        public double rate(boolean rag) {
            List<Attempt> a = attempts.stream().filter(x -> x.rag() == rag).toList();
            return a.isEmpty() ? 0 : 100.0 * a.stream().filter(Attempt::executable).count() / a.size();
        }

        public double deltaPoints() {
            return rate(true) - rate(false);
        }

        public String toMarkdown() {
            StringBuilder md = new StringBuilder();
            md.append("# SQL generation evaluation (SM-2 / SM-3)\n\n");
            md.append("- Target: ").append(label).append('\n');
            md.append("- Prompts: ").append(prompts.size()).append(" held-out; runs per configuration: ")
                    .append(runs).append('\n');
            md.append("- Executable = SQL returned and executed in a READ ONLY transaction against seed/schema.sql\n\n");
            md.append("## Executable rate\n\n| Configuration | Executable | Attempts | Rate |\n|---|---|---|---|\n");
            for (boolean rag : new boolean[] {true, false}) {
                long total = attempts.stream().filter(x -> x.rag() == rag).count();
                long ok = attempts.stream().filter(x -> x.rag() == rag && x.executable()).count();
                md.append("| RAG ").append(rag ? "on" : "off").append(" | ").append(ok).append(" | ").append(total)
                        .append(" | ").append(pct(rate(rag))).append(" |\n");
            }
            md.append("\n**RAG lift: ").append(String.format(Locale.ROOT, "%+.1f", deltaPoints()))
                    .append(" percentage points** (RAG on minus RAG off).\n\n");
            md.append("## Per prompt (Y = executable, one mark per run)\n\n")
                    .append("| Prompt | Text | RAG on | RAG off |\n|---|---|---|---|\n");
            for (EvalPrompt p : prompts) {
                md.append("| ").append(p.id()).append(" | ").append(p.prompt().replace("|", "\\|")).append(" | ")
                        .append(marks(p.id(), true)).append(" | ").append(marks(p.id(), false)).append(" |\n");
            }
            md.append("\n## Failures\n\n");
            List<Attempt> failed = attempts.stream().filter(a -> !a.executable()).toList();
            if (failed.isEmpty()) {
                md.append("None.\n");
            }
            for (Attempt a : failed) {
                md.append("- ").append(a.promptId()).append(" RAG ").append(a.rag() ? "on" : "off").append(" run ")
                        .append(a.run()).append(": ").append(oneLine(a.failure())).append('\n');
            }
            return md.toString();
        }

        private String marks(String id, boolean rag) {
            StringBuilder s = new StringBuilder();
            for (Attempt a : attempts) {
                if (a.promptId().equals(id) && a.rag() == rag) {
                    s.append(a.executable() ? 'Y' : 'N');
                }
            }
            return s.toString();
        }

        public Path write(Path file) throws IOException {
            Files.createDirectories(file.toAbsolutePath().getParent());
            return Files.writeString(file, toMarkdown(), StandardCharsets.UTF_8);
        }
    }

    private EvalHarness() {}

    /** {@code executor} returns {@code null} when the SQL executed, else the failure text. */
    public static Report run(String label, List<EvalPrompt> prompts, int runs, SqlGenerator generator,
                             Function<String, String> executor) {
        List<Attempt> attempts = new ArrayList<>();
        for (int run = 1; run <= runs; run++) {
            for (boolean rag : new boolean[] {true, false}) {
                for (EvalPrompt p : prompts) {
                    Generation g = generator.generate(p.prompt(), rag);
                    String failure;
                    if (g.sql() == null) {
                        failure = "generation " + g.error();
                    } else {
                        String err = executor.apply(g.sql());
                        failure = err == null ? null : "execution " + err;
                    }
                    attempts.add(new Attempt(p.id(), rag, run, g.sql(), failure));
                }
            }
        }
        return new Report(label, prompts, runs, List.copyOf(attempts));
    }

    static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v);
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }
}
