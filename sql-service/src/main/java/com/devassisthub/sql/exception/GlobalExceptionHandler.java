package com.devassisthub.sql.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every error to the {@code {error, message, field, details}} envelope. Never leaks exception text for 500s. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
        List<Map<String, String>> details = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.add(detail(toSnakeCase(stripIndex(fe.getField())), fe.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors().forEach(ge -> details.add(detail(null, ge.getDefaultMessage())));
        return validation(details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> details = new ArrayList<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            details.add(detail(toSnakeCase(lastNode(v.getPropertyPath().toString())), v.getMessage()));
        }
        return validation(details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(VALIDATION_FAILED, "Malformed or unreadable request body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String field = toSnakeCase(ex.getName());
        return ResponseEntity.badRequest().body(new ErrorResponse(VALIDATION_FAILED,
                "Invalid value for parameter '" + field + "'", field, null));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        String field = toSnakeCase(ex.getParameterName());
        return ResponseEntity.badRequest().body(new ErrorResponse(VALIDATION_FAILED,
                "Missing required parameter '" + field + "'", field, null));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getError(), ex.getMessage(), ex.getField(), ex.getDetails()));
    }

    /** Lost race between the version check and the flush: same 409 as a stale version. */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> handleOptimisticLock(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(CONFLICT,
                "Saved query was modified by someone else; reload and retry", "version", null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(NOT_FOUND, "Resource not found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return builder.body(ErrorResponse.of(METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported for this resource"));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
        List<Map<String, String>> details = new ArrayList<>();
        ex.getAllValidationResults().forEach(r -> {
            RequestParam rp = r.getMethodParameter().getParameterAnnotation(RequestParam.class);
            String name = rp != null && !rp.name().isEmpty() ? rp.name() : r.getMethodParameter().getParameterName();
            r.getResolvableErrors().forEach(e -> details.add(detail(toSnakeCase(name), e.getDefaultMessage())));
        });
        return validation(details);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse springError
                && !springError.getStatusCode().is5xxServerError()) {
            HttpStatusCode code = springError.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            String error = code.value() == 400 ? VALIDATION_FAILED : status != null ? status.name() : "HTTP_" + code.value();
            String message = status != null ? status.getReasonPhrase() : "Request could not be processed";
            return ResponseEntity.status(code).body(ErrorResponse.of(error, message));
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(INTERNAL_ERROR, "An unexpected error occurred"));
    }

    private static ResponseEntity<ErrorResponse> validation(List<Map<String, String>> details) {
        String field = details.isEmpty() ? null : details.get(0).get("field");
        String message = details.isEmpty() ? "Validation failed"
                : (field == null ? "" : field + ": ") + details.get(0).get("message");
        return ResponseEntity.badRequest().body(new ErrorResponse(VALIDATION_FAILED, message, field, details));
    }

    private static Map<String, String> detail(String field, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("message", message);
        return m;
    }

    /** {@code tags[0]} -> {@code tags}: element errors are reported on the collection field. */
    private static String stripIndex(String field) {
        int i = field.indexOf('[');
        return i < 0 ? field : field.substring(0, i);
    }

    private static String lastNode(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE_CASE =
            new PropertyNamingStrategies.SnakeCaseStrategy();

    /** Converts a Java property name to its snake_case JSON name, exactly as Jackson does. */
    static String toSnakeCase(String name) {
        return name == null ? null : SNAKE_CASE.translate(name);
    }
}
