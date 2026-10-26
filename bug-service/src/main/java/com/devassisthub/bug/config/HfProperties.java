package com.devassisthub.bug.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings of the OpenAI-compatible chat-completions endpoint used for triage (Hugging Face router by default). */
@ConfigurationProperties("hf")
public record HfProperties(String apiToken, String model, String baseUrl, Duration timeout, Duration connectTimeout,
                           int retryMaxAttempts, Duration retryBackoff, int maxTokens) {

    public static final String DEFAULT_MODEL = "meta-llama/Llama-3.1-8B-Instruct";
    public static final String DEFAULT_BASE_URL = "https://router.huggingface.co/v1";

    public HfProperties {
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip().replaceAll("/+$", "");
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        retryMaxAttempts = Math.max(1, retryMaxAttempts);
        retryBackoff = retryBackoff == null ? Duration.ofMillis(500) : retryBackoff;
        maxTokens = maxTokens <= 0 ? 300 : maxTokens;
    }

    public boolean configured() {
        return apiToken != null && !apiToken.isBlank();
    }
}
