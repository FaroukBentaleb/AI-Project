package com.devassisthub.sql.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/** Failure-classification helpers shared by the Hugging Face clients (embedding 5.2, generation 6.1). */
public final class HfHttp {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HfHttp() {}

    /** True when the failure is a connect/read timeout anywhere in the cause chain. */
    public static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // Spring's JDK request factory rethrows read timeouts as a plain IOException("Request timed out ...").
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException
                    || t instanceof TimeoutException
                    || (t.getMessage() != null && t.getMessage().contains("timed out"))) {
                return true;
            }
        }
        return false;
    }

    /** {@code estimated_time} of an HF 503 "model loading" body, rounded up to seconds; null when absent. */
    public static Integer estimatedTimeSeconds(String body) {
        try {
            JsonNode t = MAPPER.readTree(body == null || body.isBlank() ? "{}" : body).path("estimated_time");
            return t.isNumber() ? (int) Math.ceil(t.doubleValue()) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
