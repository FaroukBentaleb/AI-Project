package com.devassisthub.sql.rag;

import com.devassisthub.sql.llm.HfHttp;
import com.devassisthub.sql.rag.EmbeddingResult.Cause;
import com.devassisthub.sql.rag.EmbeddingResult.Embedded;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Embedding client for an OpenAI-compatible {@code /v1/embeddings} endpoint (Jina; Story 5.2). One attempt, no retries, timeout
 * {@code hf.embedding-timeout}. Every failure becomes {@link EmbeddingResult.Unavailable}; the token is never logged.
 */
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final RestClient rest;
    private final EmbeddingProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** {@code rest} must have base URL {@code hf.embedding-base-url} and the timeout configured. */
    public EmbeddingClient(RestClient rest, EmbeddingProperties props) {
        this.rest = rest;
        this.props = props;
    }

    public int dimension() {
        return props.embeddingDimension();
    }

    public EmbeddingResult embed(String text) {
        if (!props.hasToken() || !props.hasModel()) {
            return unavailable(Cause.NOT_CONFIGURED, "JINA_API_KEY or HF_EMBEDDING_MODEL is not set");
        }
        if (text == null || text.isBlank()) {
            return EmbeddingResult.unavailable(Cause.EMPTY_INPUT, "nothing to embed");
        }
        try {
            String body = rest.post()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.embeddingApiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", props.embeddingModel().strip(), "input", List.of(text),
                            "dimensions", props.embeddingDimension()))
                    .retrieve()
                    .body(String.class);
            return parse(body);
        } catch (HttpStatusCodeException e) {
            return fromStatus(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            if (HfHttp.isTimeout(e)) {
                return unavailable(Cause.TIMEOUT, "no answer within " + props.embeddingTimeout());
            }
            return unavailable(Cause.UNREACHABLE, e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private EmbeddingResult parse(String body) {
        if (body == null || body.isBlank()) {
            return unavailable(Cause.EMPTY_RESPONSE, "empty body");
        }
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (IOException e) {
            return unavailable(Cause.MALFORMED_RESPONSE, "body is not JSON");
        }
        // OpenAI-compatible answer: {"data":[{"embedding":[f, ...]}]}.
        if (node.has("data")) {
            node = node.path("data").path(0).path("embedding");
        }
        // Sentence-transformers answer [f, ...]; some providers wrap a single input as [[f, ...]].
        if (node.isArray() && node.size() == 1 && node.get(0).isArray()) {
            node = node.get(0);
        }
        if (!node.isArray() || node.isEmpty()) {
            return unavailable(Cause.MALFORMED_RESPONSE, "expected a non-empty float array");
        }
        float[] vector = new float[node.size()];
        for (int i = 0; i < vector.length; i++) {
            JsonNode v = node.get(i);
            if (!v.isNumber()) {
                return unavailable(Cause.MALFORMED_RESPONSE, "expected a flat float array");
            }
            vector[i] = v.floatValue();
        }
        if (vector.length != props.embeddingDimension()) {
            log.error("Embedding model {} returned {} dimensions but hf.embedding-dimension is {}",
                    props.embeddingModel(), vector.length, props.embeddingDimension());
            return EmbeddingResult.unavailable(Cause.DIMENSION_MISMATCH,
                    "got " + vector.length + " dimensions, expected " + props.embeddingDimension());
        }
        return new Embedded(vector);
    }

    private EmbeddingResult fromStatus(int status, String body) {
        if (status == 401 || status == 403) {
            return unavailable(Cause.INVALID_TOKEN, "provider rejected the token (" + status + ")");
        }
        if (status == 429) {
            return unavailable(Cause.QUOTA_EXCEEDED, "rate limit or quota exceeded (429)");
        }
        if (status == 503) {
            Integer retryAfter = HfHttp.estimatedTimeSeconds(body);
            if (retryAfter != null) {
                log.warn("Embedding unavailable (MODEL_LOADING): retry in ~{} s", retryAfter);
                return new EmbeddingResult.Unavailable(Cause.MODEL_LOADING, "model loading", retryAfter);
            }
        }
        return unavailable(Cause.PROVIDER_ERROR, "provider answered " + status);
    }


    private EmbeddingResult unavailable(Cause cause, String detail) {
        log.warn("Embedding unavailable ({}): {}", cause, detail);
        return EmbeddingResult.unavailable(cause, detail);
    }
}
