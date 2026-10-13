package com.devassisthub.commit.rag;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {
    @Bean
    QdrantClient qdrantClient(
            @Value("${qdrant.host:qdrant}") String host,
            @Value("${qdrant.port:6334}") int port) {
        // TODO: Configure TLS and lifecycle management for production.
        return new QdrantClient(QdrantGrpcClient.newBuilder(host, port, false).build());
    }
}
