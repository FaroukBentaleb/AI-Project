package com.devassisthub.sql.exception;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleException(Exception exception) {
        return ResponseEntity.internalServerError().body(Map.of("timestamp", Instant.now(), "status", 500,
                "error", "Internal Server Error", "message", exception.getMessage() == null ? "Unexpected error" : exception.getMessage()));
    }
}
