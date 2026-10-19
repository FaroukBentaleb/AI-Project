package com.devassisthub.sql.llm;

import com.devassisthub.sql.llm.LlmResult.Cause;
import com.devassisthub.sql.llm.LlmResult.Completed;
import com.devassisthub.sql.llm.LlmResult.Failed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Dedicated Hugging Face text-generation client (Story 6.1). One attempt, no transport retries, timeout
 * {@code hf.timeout}, greedy decoding. Never logs the token or the prompt.
 */
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestClient rest;
    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** {@code rest} must have base URL {@code hf.base-url} and the timeout configured. */
    public LlmClient(RestClient rest, LlmProperties props) {
        this.rest = rest;
        this.props = props;
    }

    public String model() {
        return props.model();
    }

    public LlmResult complete(String prompt) {
        if (!props.hasToken()) {
            return failed(Cause.NOT_CONFIGURED, "HF_API_TOKEN is not set", null);
        }
        // do_sample=false is greedy decoding (temperature 0); TGI rejects an explicit temperature of 0.
        Map<String, Object> body = Map.of("inputs", prompt,
                "parameters", Map.of("max_new_tokens", props.maxNewTokens(), "do_sample", false,
                        "return_full_text", false));
        try {
            String reply = rest.post()
                    // HF model ids are "org/name": keep the slash, encode each segment.
                    .uri(b -> b.pathSegment(props.model().split("/")).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(reply);
        } catch (HttpStatusCodeException e) {
            return fromStatus(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            if (HfHttp.isTimeout(e)) {
                return failed(Cause.TIMEOUT, "no answer within " + props.timeout(), null);
            }
            return failed(Cause.UNREACHABLE, e.getClass().getSimpleName(), null);
        }
    }

    /** Accepts {@code [{"generated_text": ...}]} or {@code {"generated_text": ...}}; an empty body is empty text. */
    private LlmResult parse(String body) {
        if (body == null || body.isBlank()) {
            return new Completed("");
        }
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (IOException e) {
            return failed(Cause.MALFORMED_RESPONSE, "body is not JSON", null);
        }
        if (node.isArray() && !node.isEmpty()) {
            node = node.get(0);
        }
        JsonNode text = node.path("generated_text");
        if (!text.isTextual()) {
            return failed(Cause.MALFORMED_RESPONSE, "no generated_text in reply", null);
        }
        return new Completed(text.asText());
    }

    private LlmResult fromStatus(int status, String body) {
        if (status == 401 || status == 403) {
            return failed(Cause.INVALID_TOKEN, "provider rejected the token (" + status + ")", null);
        }
        if (status == 429) {
            return failed(Cause.QUOTA_EXCEEDED, "rate limit or quota exceeded (429)", null);
        }
        if (status == 503) {
            Integer retryAfter = HfHttp.estimatedTimeSeconds(body);
            if (retryAfter != null) {
                return failed(Cause.MODEL_LOADING, "model loading", retryAfter);
            }
        }
        return failed(Cause.PROVIDER_ERROR, "provider answered " + status, null);
    }

    private Failed failed(Cause cause, String detail, Integer retryAfter) {
        log.warn("LLM call failed ({}): {}", cause, detail);
        return new Failed(cause, detail, retryAfter);
    }
}
