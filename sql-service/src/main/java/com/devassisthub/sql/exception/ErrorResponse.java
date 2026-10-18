package com.devassisthub.sql.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Standard error envelope. Null members are always serialized so the shape is stable. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorResponse(String error, String message, String field, Object details) {
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null, null);
    }
}
