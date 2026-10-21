package com.devassisthub.sql.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/** Request-scoped caller identity resolved from the {@code X-User-Id} header. */
@Component
@RequestScope
public class CallerIdentity {

    public static final String HEADER = "X-User-Id";
    public static final String ANONYMOUS = "anonymous";

    private final String userId;

    public CallerIdentity(HttpServletRequest request) {
        this.userId = resolve(request.getHeader(HEADER));
    }

    static String resolve(String header) {
        if (header == null || header.isBlank()) {
            return ANONYMOUS;
        }
        return header.trim();
    }

    public String getUserId() {
        return userId;
    }
}
