package com.devassisthub.sql.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    public PromptTemplates(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalStateException("sql.prompt.version must match " + VERSION + ": " + version);
        }
        this.version = version;
        this.generate = load(version, "generate.txt");
    }

    public String version() {
        return version;
    }

    public String renderGenerate(String prompt, String dialect) {
        String clean = MARKERS.matcher(prompt).replaceAll(" ").strip();
        // Dialect first, so user text is never re-scanned for placeholders.
        return generate.replace("{{dialect}}", dialect).replace("{{prompt}}", clean);
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
