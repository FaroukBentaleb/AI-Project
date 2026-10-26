package com.devassisthub.bug.exception;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every error to the {@code {error, message, field, details}} envelope; 500s never leak exception text. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE_CASE =
            new PropertyNamingStrategies.SnakeCaseStrategy();

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, ex.getRetryAfterSeconds().toString());
        }
        return builder.body(new ErrorResponse(ex.getError(), ex.getMessage(), ex.getField(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
        List<Map<String, String>> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", jsonName(fe.getField()), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        FieldError first = ex.getBindingResult().getFieldError();
        String field = first == null ? null : jsonName(first.getField());
        String message = first == null ? "The request is invalid" : field + ": " + first.getDefaultMessage();
        return ResponseEntity.unprocessableEntity().body(new ErrorResponse(VALIDATION_FAILED, message, field, details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        // An unknown enum value ("severity": "HUGE") deserves a message listing the accepted values.
        if (ex.getCause() instanceof InvalidFormatException ife && ife.getTargetType() != null
                && ife.getTargetType().isEnum() && !ife.getPath().isEmpty()) {
            String field = ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            String allowed = Arrays.stream(ife.getTargetType().getEnumConstants()).map(String::valueOf)
                    .collect(Collectors.joining(", "));
            return ResponseEntity.unprocessableEntity().body(new ErrorResponse(VALIDATION_FAILED,
                    field + ": must be one of " + allowed, field, null));
        }
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(VALIDATION_FAILED, "The request body is missing or not valid JSON"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String field = jsonName(ex.getName());
        String message = "Invalid value for '" + field + "'";
        Class<?> type = ex.getRequiredType();
        if (type != null && type.isEnum()) {
            message += "; use one of " + Arrays.stream(type.getEnumConstants()).map(String::valueOf)
                    .collect(Collectors.joining(", "));
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(VALIDATION_FAILED, message, field, null));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        String field = jsonName(ex.getParameterName());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(VALIDATION_FAILED, "'" + field + "' is required", field, null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse.of("METHOD_NOT_ALLOWED",
                "Method " + ex.getMethod() + " is not supported for this resource"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("NOT_FOUND", "No such endpoint"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(INTERNAL_ERROR, "An unexpected error occurred"));
    }

    /** {@code tags[0]} -> {@code tags}, {@code stepsToReproduce} -> {@code steps_to_reproduce}, as the JSON names it. */
    static String jsonName(String field) {
        if (field == null) {
            return null;
        }
        int bracket = field.indexOf('[');
        return SNAKE_CASE.translate(bracket < 0 ? field : field.substring(0, bracket));
    }
}
