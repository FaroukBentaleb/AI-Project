package com.devassisthub.sql.support;

import java.io.IOException;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Stands in for the Hugging Face feature-extraction endpoint in every IT: answers a deterministic 384-dim vector
 * derived from the request body, so no real HF call is ever made. Latency and failure are switchable per test.
 */
public final class StubEmbeddingServer {

    public static final int DIMENSION = 384;
    private static final MockWebServer SERVER = new MockWebServer();
    private static volatile long delayMillis;
    private static volatile int failStatus;
    /** Story 6.4: bodies containing one of these needles all get the same vector (score 1.0 against each other). */
    private static final java.util.Set<String> PINS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        SERVER.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (failStatus != 0) {
                    return new MockResponse().setResponseCode(failStatus);
                }
                MockResponse r = new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(vector(seed(request.getBody().readUtf8())));
                return delayMillis > 0 ? r.setHeadersDelay(delayMillis, TimeUnit.MILLISECONDS) : r;
            }
        });
        try {
            SERVER.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private StubEmbeddingServer() {
    }

    public static String baseUrl() {
        return SERVER.url("/models").toString();
    }

    public static void delay(long millis) {
        delayMillis = millis;
    }

    /** Non-zero: every call answers this HTTP status. */
    public static void failWith(int status) {
        failStatus = status;
    }

    /** Every embedded text containing {@code needle} gets one fixed vector, until {@link #reset()}. */
    public static void pin(String needle) {
        PINS.add(needle);
    }

    public static void reset() {
        delayMillis = 0;
        failStatus = 0;
        PINS.clear();
    }

    private static String seed(String body) {
        for (String needle : PINS) {
            if (body.contains(needle)) {
                return "pin:" + needle;
            }
        }
        return body;
    }

    private static String vector(String seed) {
        Random rnd = new Random(seed.hashCode());
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (int i = 0; i < DIMENSION; i++) {
            j.add(Float.toString(rnd.nextFloat() - 0.5f));
        }
        return j.toString();
    }
}
