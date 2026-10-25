package com.devassisthub.sql.rag;

/**
 * Outcome of an embedding call: either a vector or a typed "embedding unavailable". Callers never see an exception.
 */
public sealed interface EmbeddingResult {

    record Embedded(float[] vector) implements EmbeddingResult {
    }

    /**
     * @param retryAfterSeconds provider estimate (503 model loading, rounded up), else null
     */
    record Unavailable(Cause cause, String detail, Integer retryAfterSeconds) implements EmbeddingResult {
    }

    /** Distinct failure causes; {@link #errorCode()} maps each to the FR-19 code family. */
    enum Cause {
        /** No token or no model configured: no HTTP call is made. */
        NOT_CONFIGURED("LLM_NOT_CONFIGURED"),
        /** 401 or 403: the provider rejected the token. */
        INVALID_TOKEN("LLM_NOT_CONFIGURED"),
        /** Vector length differs from {@code hf.embedding-dimension}: a configuration error. */
        DIMENSION_MISMATCH("LLM_NOT_CONFIGURED"),
        /** 429. */
        QUOTA_EXCEEDED("LLM_QUOTA_EXCEEDED"),
        /** 503 with {@code estimated_time}. */
        MODEL_LOADING("LLM_UNAVAILABLE"),
        /** Other 5xx or unexpected 4xx. */
        PROVIDER_ERROR("LLM_UNAVAILABLE"),
        /** No answer within {@code hf.embedding-timeout}. */
        TIMEOUT("LLM_UNAVAILABLE"),
        /** Connection refused, DNS failure, etc. */
        UNREACHABLE("LLM_UNAVAILABLE"),
        /** 2xx with no body. */
        EMPTY_RESPONSE("LLM_UNAVAILABLE"),
        /** 2xx body that is not a single float vector. */
        MALFORMED_RESPONSE("LLM_UNAVAILABLE"),
        /** Blank text to embed; no HTTP call is made. */
        EMPTY_INPUT("LLM_UNAVAILABLE");

        private final String errorCode;

        Cause(String errorCode) {
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    static Unavailable unavailable(Cause cause, String detail) {
        return new Unavailable(cause, detail, null);
    }
}
