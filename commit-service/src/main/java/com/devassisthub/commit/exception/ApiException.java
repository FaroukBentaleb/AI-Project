package com.devassisthub.commit.exception;

import org.springframework.http.HttpStatus;

/** An expected failure with the HTTP status and stable error code the client should see. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final String field;

    public ApiException(HttpStatus status, String error, String message) {
        this(status, error, message, null);
    }

    public ApiException(HttpStatus status, String error, String message, String field) {
        super(message);
        this.status = status;
        this.error = error;
        this.field = field;
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

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException invalidUpload(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_UPLOAD", message, "file");
    }
}
