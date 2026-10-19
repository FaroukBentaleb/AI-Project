package com.devassisthub.sql.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Generation settings under {@code hf.} (Story 6.1). {@link #toString()} never includes the token. */
@ConfigurationProperties("hf")
public record LlmProperties(String apiToken, String model, Duration timeout, String baseUrl, int maxNewTokens) {

    public static final String DEFAULT_MODEL = "defog/sqlcoder-7b-2";

    public LlmProperties {
        // Compose passes HF_MODEL as an empty string when unset: fall back to the default model.
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        maxNewTokens = maxNewTokens <= 0 ? 400 : maxNewTokens;
    }

    public boolean hasToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    @Override
    public String toString() {
        return "LlmProperties[apiToken=" + (hasToken() ? "***" : "<unset>") + ", model=" + model + ", timeout="
                + timeout + ", baseUrl=" + baseUrl + ", maxNewTokens=" + maxNewTokens + "]";
    }
}
