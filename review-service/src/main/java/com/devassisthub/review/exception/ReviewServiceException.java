package com.devassisthub.review.exception;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;

public class ReviewServiceException extends RuntimeException {

    @NonNull
    private final HttpStatus status;

    public ReviewServiceException(@NonNull HttpStatus status, String message) {
        super(message);
        this.status = Objects.requireNonNull(status);
    }

    @NonNull
    public HttpStatus getStatus() {
        return status;
    }
}
