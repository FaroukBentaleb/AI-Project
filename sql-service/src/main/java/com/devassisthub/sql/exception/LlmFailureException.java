package com.devassisthub.sql.exception;

import com.devassisthub.sql.dto.GenerationErrorDetails;
import com.devassisthub.sql.llm.LlmResult;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Story 6.2 (FR-19): a 503 LLM failure with its user-facing message (EXPERIENCE.md copy) and optional retry hint. */
public class LlmFailureException extends ApiException {

    public static final String UNAVAILABLE_MESSAGE =
            "SQL generation temporarily unavailable. You can save a query manually.";
    public static final String QUOTA_MESSAGE =
            "The team's AI quota is used up for now. You can save a query manually.";
    public static final String NOT_CONFIGURED_MESSAGE =
            "SQL generation isn't configured on this server. You can save a query manually.";

    private final Integer retryAfterSeconds;

    private LlmFailureException(String error, String message, Integer retryAfterSeconds) {
        super(HttpStatus.SERVICE_UNAVAILABLE, error, message, null,
                new GenerationErrorDetails(null, List.of(), null, retryAfterSeconds, null));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Maps a typed client failure; the provider detail stays in the log, never in the response. */
    public static LlmFailureException of(LlmResult.Failed failed) {
        String code = failed.cause().errorCode();
        return switch (code) {
            case "LLM_QUOTA_EXCEEDED" -> new LlmFailureException(code, QUOTA_MESSAGE, failed.retryAfterSeconds());
            // Retrying cannot help a misconfigured server: no hint.
            case "LLM_NOT_CONFIGURED" -> new LlmFailureException(code, NOT_CONFIGURED_MESSAGE, null);
            default -> new LlmFailureException(code, UNAVAILABLE_MESSAGE, failed.retryAfterSeconds());
        };
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
