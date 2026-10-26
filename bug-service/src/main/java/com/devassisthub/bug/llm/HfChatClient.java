package com.devassisthub.bug.llm;

import com.devassisthub.bug.config.HfProperties;
import com.devassisthub.bug.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Dedicated client of an OpenAI-compatible {@code POST {base-url}/chat/completions} endpoint (the Hugging Face router
 * by default). Strict connect/read timeouts; transport failures, timeouts and 5xx are retried with a backoff, while
 * auth, quota and other 4xx answers fail at once with a clear error code. Never logs the token or the prompt.
 */
@Component
public class HfChatClient {

    /** One chat message: {@code role} is "system", "user" or "assistant". */
    public record Message(String role, String content) {
    }

    private static final Logger log = LoggerFactory.getLogger(HfChatClient.class);

    private final HfProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public HfChatClient(HfProperties props) {
        this.props = props;
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.timeout());
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    public boolean configured() {
        return props.configured();
    }

    public String model() {
        return props.model();
    }

    /** Host of the endpoint, for the status endpoint and logs. */
    public String provider() {
        try {
            return URI.create(props.baseUrl()).getHost();
        } catch (IllegalArgumentException e) {
            return props.baseUrl();
        }
    }

    /** Returns the assistant's text, or throws an {@link ApiException} naming the failure. */
    public String complete(List<Message> messages) {
        if (!props.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_NOT_CONFIGURED",
                    "AI triage is not configured: set HF_API_TOKEN (a Hugging Face access token) and restart "
                            + "bug-service");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("messages", messages);
        body.put("max_tokens", props.maxTokens());
        body.put("temperature", 0.1);
        body.put("stream", false);

        ApiException last = null;
        int attempts = props.retryMaxAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String reply = rest.post()
                        .uri("/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return content(reply);
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (!e.getStatusCode().is5xxServerError()) {
                    throw fromClientError(status, e.getResponseBodyAsString(), e.getResponseHeaders());
                }
                last = new ApiException(HttpStatus.BAD_GATEWAY, "LLM_UNAVAILABLE",
                        "The AI provider is failing (HTTP " + status + "); try again in a moment");
            } catch (ResourceAccessException e) {
                last = isTimeout(e)
                        ? new ApiException(HttpStatus.GATEWAY_TIMEOUT, "LLM_TIMEOUT",
                                "The AI provider did not answer within " + props.timeout().toSeconds() + " s")
                        : new ApiException(HttpStatus.BAD_GATEWAY, "LLM_UNAVAILABLE",
                                "The AI provider is unreachable from bug-service");
            }
            log.warn("LLM call failed (attempt {}/{}): {}", attempt, attempts, last.getError());
            if (attempt < attempts) {
                sleep(props.retryBackoff().toMillis() * attempt);
            }
        }
        throw last;
    }

    /** {@code choices[0].message.content} of a chat-completions reply. */
    private String content(String reply) {
        JsonNode node;
        try {
            node = mapper.readTree(reply == null ? "" : reply);
        } catch (IOException e) {
            throw invalid("The AI provider answered with something that is not JSON");
        }
        JsonNode content = node == null ? null : node.path("choices").path(0).path("message").path("content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            throw invalid("The AI provider answered without any text");
        }
        return content.asText();
    }

    private ApiException fromClientError(int status, String body, HttpHeaders headers) {
        if (status == 401 || status == 403) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "LLM_INVALID_TOKEN",
                    "The AI provider rejected the access token (HTTP " + status + "); check HF_API_TOKEN");
        }
        if (status == 402) {
            return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LLM_QUOTA_EXCEEDED",
                    "The AI provider quota is used up (no inference credits left on this token)");
        }
        if (status == 429) {
            return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LLM_QUOTA_EXCEEDED",
                    "The AI provider rate limit was reached; wait a moment and try again", null,
                    retryAfter(headers));
        }
        String lower = body == null ? "" : body.toLowerCase();
        if (status == 404 || lower.contains("model_not_supported") || lower.contains("model_not_found")
                || lower.contains("does not exist")) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MODEL_UNAVAILABLE",
                    "The model '" + props.model() + "' is not available from the AI provider; set HF_MODEL to a "
                            + "supported chat model");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "LLM_REJECTED",
                "The AI provider rejected the request (HTTP " + status + ")");
    }

    private static Integer retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            int seconds = (int) Math.ceil(Double.parseDouble(value.strip()));
            return seconds > 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException
                    || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "LLM_INVALID_OUTPUT", message);
    }
}
