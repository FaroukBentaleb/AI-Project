package com.devassisthub.review.exception;

import org.springframework.http.HttpStatus;

public class ReviewServiceException extends RuntimeException {

    private final HttpStatus status;

    public ReviewServiceException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
