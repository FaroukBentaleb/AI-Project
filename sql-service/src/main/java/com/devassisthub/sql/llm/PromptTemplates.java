package com.devassisthub.sql.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import com.devassisthub.sql.rag.ExampleRetriever;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * FR-20: versioned prompt templates loaded from {@code classpath:prompts/<version>/}. Switching
 * {@code sql.prompt.version} changes the template without Java changes. A missing template fails startup.
 */
public class PromptTemplates {

    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9_-]+");
    /** Markers of the sqlcoder prompt format; stripped from user text so it cannot close or open a section. */
    private static final Pattern MARKERS = Pattern.compile("(?i)\\[/?(QUESTION|SQL)]|#{3,}");

    private final String version;
    private final String generate;
    private final String retry;
    /** Story 6.3: the previous reply quoted in the corrective prompt is capped to keep the prompt small. */
    static final int PREVIOUS_LIMIT = 1000;
    /** Story 6.4 (FR-14): per-example cap and total cap on the example blocks. */
    public static final int EXAMPLE_LIMIT = 2000;
    public static final int EXAMPLES_TOTAL_LIMIT = 8000;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Pattern EXAMPLE_TAG = Pattern.compile("(?i)</?\\s*example[^>]*>?");
    private final String schemaNone;
    private final String schemaExamples;

    public PromptTemplates(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalStateException("sql.prompt.version must match " + VERSION + ": " + version);
        }
        this.version = version;
        this.generate = load(version, "generate.txt");
        this.retry = load(version, "retry.txt");
        this.schemaNone = load(version, "schema-none.txt").strip();
        this.schemaExamples = load(version, "schema-examples.txt").strip();
    }

    public String version() {
        return version;
    }

    public String renderGenerate(String prompt, String dialect) {
        return renderGenerate(prompt, dialect, List.of());
    }

    /** Story 6.4: {@code {{schema}}} becomes the example blocks, or the zero-shot text when there are none. */
    public String renderGenerate(String prompt, String dialect, List<ExampleRetriever.Example> examples) {
        String clean = MARKERS.matcher(prompt).replaceAll(" ").strip();
        return fill(generate, Map.of("dialect", dialect, "prompt", clean, "schema", schema(examples)));
    }

    public String renderRetry(String prompt, String dialect, String previous) {
        return renderRetry(prompt, dialect, previous, List.of());
    }

    /** Story 6.3 (FR-17): corrective prompt quoting the unusable previous reply ({@code {{previous}}}). */
    public String renderRetry(String prompt, String dialect, String previous,
                              List<ExampleRetriever.Example> examples) {
        String clean = MARKERS.matcher(prompt).replaceAll(" ").strip();
        String prev = previous == null ? "" : previous.strip();
        if (prev.length() > PREVIOUS_LIMIT) {
            prev = prev.substring(0, PREVIOUS_LIMIT);
        }
        prev = MARKERS.matcher(prev).replaceAll(" ").strip();
        if (prev.isEmpty()) {
            prev = "(empty reply)";
        }
        return fill(retry, Map.of("dialect", dialect, "prompt", clean, "previous", prev,
                "schema", schema(examples)));
    }

    /**
     * Story 6.4: the examples that fit in the prompt, in rank order. Each block is capped at
     * {@link #EXAMPLE_LIMIT}; blocks that would push the total past {@link #EXAMPLES_TOTAL_LIMIT} are dropped.
     */
    public static List<ExampleRetriever.Example> fitting(List<ExampleRetriever.Example> examples) {
        List<ExampleRetriever.Example> out = new ArrayList<>();
        int total = 0;
        for (ExampleRetriever.Example e : examples) {
            int len = block(e).length();
            if (total + len > EXAMPLES_TOTAL_LIMIT) {
                break;
            }
            total += len;
            out.add(e);
        }
        return out;
    }

    private String schema(List<ExampleRetriever.Example> examples) {
        List<ExampleRetriever.Example> fit = fitting(examples);
        if (fit.isEmpty()) {
            return schemaNone;
        }
        StringBuilder blocks = new StringBuilder();
        for (ExampleRetriever.Example e : fit) {
            blocks.append(block(e)).append('\n');
        }
        return fill(schemaExamples, Map.of("examples", blocks.toString().strip()));
    }

    /** One delimited data block; intent and SQL are sanitised, then the body is capped at 2,000 chars. */
    static String block(ExampleRetriever.Example e) {
        String body = "-- intent: " + sanitize(e.intent()).replaceAll("\\s+", " ") + "\n" + sanitize(e.sql());
        if (body.length() > EXAMPLE_LIMIT) {
            body = body.substring(0, EXAMPLE_LIMIT);
        }
        return "<example id=\"" + e.id() + "\">\n" + body.strip() + "\n</example>";
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String s = MARKERS.matcher(text).replaceAll(" ");
        return EXAMPLE_TAG.matcher(s).replaceAll("(example)").strip();
    }

    /** Single pass: inserted values are never re-scanned, so untrusted text cannot inject a placeholder. */
    private static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String load(String version, String name) {
        ClassPathResource res = new ClassPathResource("prompts/" + version + "/" + name);
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Prompt template not found: prompts/" + version + "/" + name, e);
        }
    }
}
