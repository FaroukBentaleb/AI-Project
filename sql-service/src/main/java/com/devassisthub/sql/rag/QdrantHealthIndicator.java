package com.devassisthub.sql.rag;

import java.util.Optional;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/** {@code qdrant} contributor for the {@code dependencies} health group: one cheap REST read, no LLM calls. */
public class QdrantHealthIndicator extends AbstractHealthIndicator {

    private final QdrantClient client;
    private final CollectionBootstrap bootstrap;

    public QdrantHealthIndicator(QdrantClient client, CollectionBootstrap bootstrap) {
        super("Qdrant health check failed");
        this.client = client;
        this.bootstrap = bootstrap;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("collection", client.collection());
        Optional<CollectionInfo> info = client.getCollection();
        if (info.isEmpty()) {
            builder.down().withDetail("error", "collection missing (bootstrap pending)");
            return;
        }
        builder.up().withDetail("vector_size", info.get().vectorSize())
                .withDetail("distance", info.get().distance())
                .withDetail("points_count", info.get().pointsCount());
        if (bootstrap.sizeMismatch() != null) {
            builder.down().withDetail("vector_size_mismatch", bootstrap.sizeMismatch());
        }
    }
}
