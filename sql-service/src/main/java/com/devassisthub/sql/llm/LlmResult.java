package com.devassisthub.sql.llm;

/** Outcome of one completion call: the generated text, or a typed failure. Callers never see an exception. */
public sealed interface LlmResult {

    /** {@code text} is the raw completion, unvalidated. */
    record Completed(String text) implements LlmResult {
    }

    /** @param retryAfterSeconds provider estimate (503 model loading, rounded up), else null */
    record Failed(Cause cause, String detail, Integer retryAfterSeconds) implements LlmResult {
    }

    /** Distinct failure causes; {@link #errorCode()} is the FR-19 code (same families as the embedding client). */
    enum Cause {
        NOT_CONFIGURED("LLM_NOT_CONFIGURED"),
        INVALID_TOKEN("LLM_NOT_CONFIGURED"),
        QUOTA_EXCEEDED("LLM_QUOTA_EXCEEDED"),
        MODEL_LOADING("LLM_UNAVAILABLE"),
        PROVIDER_ERROR("LLM_UNAVAILABLE"),
        TIMEOUT("LLM_UNAVAILABLE"),
        UNREACHABLE("LLM_UNAVAILABLE"),
        MALFORMED_RESPONSE("LLM_UNAVAILABLE");

        private final String errorCode;

        Cause(String errorCode) {
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
