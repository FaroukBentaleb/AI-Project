package com.devassisthub.bug.exception;

import org.springframework.http.HttpStatus;

/** An expected failure with the HTTP status and stable error code the client should see. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final String field;
    private final Integer retryAfterSeconds;

    public ApiException(HttpStatus status, String error, String message) {
        this(status, error, message, null, null);
    }

    public ApiException(HttpStatus status, String error, String message, String field, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.error = error;
        this.field = field;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getField() {
        return field;
    }

    /** Seconds the client should wait before retrying (sent as {@code Retry-After}), or null. */
    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException notFound(long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Bug " + id + " not found");
    }
}
