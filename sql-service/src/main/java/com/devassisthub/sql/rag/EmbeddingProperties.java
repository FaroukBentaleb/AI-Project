package com.devassisthub.sql.rag;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Embedding settings under {@code hf.} (Story 5.2); the token is {@code hf.embedding-api-token} (Jina). {@link #toString()} never includes the token. */
@ConfigurationProperties("hf")
public record EmbeddingProperties(String embeddingApiToken, String embeddingModel, int embeddingDimension,
                                  Duration embeddingTimeout, String embeddingBaseUrl) {

    public boolean hasToken() {
        return embeddingApiToken != null && !embeddingApiToken.isBlank();
    }

    public boolean hasModel() {
        return embeddingModel != null && !embeddingModel.isBlank();
    }

    @Override
    public String toString() {
        return "EmbeddingProperties[embeddingApiToken=" + (hasToken() ? "***" : "<unset>") + ", embeddingModel=" + embeddingModel
                + ", embeddingDimension=" + embeddingDimension + ", embeddingTimeout=" + embeddingTimeout
                + ", embeddingBaseUrl=" + embeddingBaseUrl + "]";
    }
}
