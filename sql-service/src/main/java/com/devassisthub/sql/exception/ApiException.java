package com.devassisthub.sql.exception;

import org.springframework.http.HttpStatus;

/** Base exception for expected API errors (404, 409, ...) mapped to the error envelope. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String error;
    private final String field;
    private final transient Object details;

    public ApiException(HttpStatus status, String error, String message) {
        this(status, error, message, null, null);
    }

    public ApiException(HttpStatus status, String error, String message, String field, Object details) {
        super(message);
        this.status = status;
        this.error = error;
        this.field = field;
        this.details = details;
    }

    public HttpStatus getStatus() { return status; }
    public String getError() { return error; }
    public String getField() { return field; }
    public Object getDetails() { return details; }
}
