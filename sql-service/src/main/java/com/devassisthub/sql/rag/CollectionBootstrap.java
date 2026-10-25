package com.devassisthub.sql.rag;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * FR-21: ensures {@code sql_embeddings} exists (cosine, configured size) once the app is up, and retries every
 * {@code qdrant.bootstrap-retry} while Qdrant is unreachable. Never blocks or fails startup, never recreates or
 * alters an existing collection.
 */
public class CollectionBootstrap implements ApplicationEventPublisherAware {

    private static final Logger log = LoggerFactory.getLogger(CollectionBootstrap.class);

    private final QdrantClient client;
    private final int vectorSize;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicReference<String> sizeMismatch = new AtomicReference<>();
    private ApplicationEventPublisher events;

    public CollectionBootstrap(QdrantClient client, int vectorSize) {
        this.client = client;
        this.vectorSize = vectorSize;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        tryBootstrap();
    }

    @Scheduled(initialDelayString = "${qdrant.bootstrap-retry}", fixedDelayString = "${qdrant.bootstrap-retry}")
    public void retry() {
        if (!ready.get()) {
            tryBootstrap();
        }
    }

    /** Returns true when the collection is known to exist (now or from an earlier attempt). */
    public synchronized boolean tryBootstrap() {
        if (ready.get()) {
            return true;
        }
        try {
            Optional<CollectionInfo> existing = client.getCollection();
            if (existing.isPresent()) {
                checkSize(existing.get());
            } else if (client.createCollection(vectorSize)) {
                log.info("Created Qdrant collection '{}' (size {}, Cosine)", client.collection(), vectorSize);
            } else {
                client.getCollection().ifPresent(this::checkSize);
            }
            ready.set(true);
            if (events != null) {
                // Story 5.4: first readiness (startup or delayed retry) triggers Reconciliation.
                events.publishEvent(new CollectionReadyEvent(client.collection()));
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Qdrant bootstrap of '{}' failed, will retry: {}", client.collection(), e.getMessage());
            return false;
        }
    }

    private void checkSize(CollectionInfo info) {
        if (info.vectorSize() != vectorSize) {
            String msg = "collection size " + info.vectorSize() + " != configured " + vectorSize;
            sizeMismatch.set(msg);
            log.error("Qdrant collection '{}' vector size mismatch: {} (not modified; drop it and reindex to change "
                    + "the embedding model)", info.name(), msg);
        } else {
            sizeMismatch.set(null);
        }
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.events = publisher;
    }

    public boolean isReady() {
        return ready.get();
    }

    public int vectorSize() {
        return vectorSize;
    }

    /** Non-null when the existing collection's size disagrees with {@code hf.embedding-dimension}. */
    public String sizeMismatch() {
        return sizeMismatch.get();
    }
}
