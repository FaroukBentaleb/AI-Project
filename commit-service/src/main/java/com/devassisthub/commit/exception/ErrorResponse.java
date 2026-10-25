package com.devassisthub.commit.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Standard error envelope, same shape as the other services. Null members are always serialized. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorResponse(String error, String message, String field, Object details) {
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null, null);
    }
}
