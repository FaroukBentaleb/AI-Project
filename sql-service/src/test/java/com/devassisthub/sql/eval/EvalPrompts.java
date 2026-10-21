package com.devassisthub.sql.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Story 6.6: the 15 held-out evaluation prompts in {@code eval/prompts.json} (SM-C2 audited). */
public final class EvalPrompts {

    public static final String RESOURCE = "eval/prompts.json";

    public record EvalPrompt(String id, String prompt) {}

    private EvalPrompts() {}

    public static List<EvalPrompt> load() {
        try (InputStream in = EvalPrompts.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            List<EvalPrompt> out = new ArrayList<>();
            for (JsonNode p : root.get("prompts")) {
                out.add(new EvalPrompt(p.get("id").asText(), p.get("prompt").asText()));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable " + RESOURCE, e);
        }
    }
}
