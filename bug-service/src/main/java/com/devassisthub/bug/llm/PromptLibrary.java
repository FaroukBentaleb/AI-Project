package com.devassisthub.bug.llm;

import com.devassisthub.bug.config.TriageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The versioned triage prompts under {@code classpath:prompts/<version>/}, loaded once at startup so a missing file
 * stops the service instead of failing on the first request. Placeholders are written {@code {{name}}}.
 */
@Component
public class PromptLibrary {

    private final String version;
    private final String system;
    private final String user;
    private final String repair;

    public PromptLibrary(TriageProperties props) {
        this.version = props.promptVersion();
        this.system = load("triage-system.txt");
        this.user = load("triage-user.txt");
        this.repair = load("repair.txt");
    }

    public String version() {
        return version;
    }

    public String system() {
        return system;
    }

    public String user(Map<String, String> values) {
        return render(user, values);
    }

    public String repair(Map<String, String> values) {
        return render(repair, values);
    }

    /** Replaces each {@code {{key}}}; values are inserted literally (no recursive substitution). */
    static String render(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length() + 256);
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            int close = open < 0 ? -1 : template.indexOf("}}", open + 2);
            if (open < 0 || close < 0) {
                out.append(template, i, template.length());
                break;
            }
            String key = template.substring(open + 2, close).strip();
            out.append(template, i, open);
            out.append(values.containsKey(key) ? values.get(key) : template.substring(open, close + 2));
            i = close + 2;
        }
        return out.toString();
    }

    private String load(String name) {
        String path = "prompts/" + version + "/" + name;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        } catch (IOException e) {
            throw new IllegalStateException("Prompt file classpath:" + path + " is missing (bug.triage.prompt-version="
                    + version + ")", e);
        }
    }
}
