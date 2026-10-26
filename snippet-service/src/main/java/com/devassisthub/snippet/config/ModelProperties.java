package com.devassisthub.snippet.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection and input-size settings for the FastAPI model service. */
@ConfigurationProperties("snippet.model")
public record ModelProperties(String url, Duration connectTimeout, Duration readTimeout, int retryMaxAttempts,
                              int maxCodeChars) {
}
