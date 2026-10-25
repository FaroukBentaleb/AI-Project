package com.devassisthub.sql.rag;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Qdrant connection settings (Story 5.1). sql-service talks REST on {@code restPort}; {@code port} is the gRPC port
 * from compose ({@code QDRANT_PORT}), bound but reserved. {@code collection} is the only collection this service owns.
 */
@ConfigurationProperties("qdrant")
public record QdrantProperties(String host, int port, int restPort, Duration timeout, String collection,
                               Duration bootstrapRetry) {

    public String baseUrl() {
        return "http://" + host + ":" + restPort;
    }
}
