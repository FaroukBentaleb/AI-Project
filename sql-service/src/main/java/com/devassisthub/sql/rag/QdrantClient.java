package com.devassisthub.sql.rag;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Minimal Qdrant REST client scoped to sql-service's own collection. It only ever reads or creates
 * {@code qdrant.collection}; it has no call that could touch another collection.
 * Transport failures surface as {@link org.springframework.web.client.RestClientException}.
 */
public class QdrantClient {

    private final RestClient rest;
    private final String collection;

    public QdrantClient(RestClient rest, String collection) {
        this.rest = rest;
        this.collection = collection;
    }

    public String collection() {
        return collection;
    }

    /** The collection's info, or empty if it does not exist. */
    public Optional<CollectionInfo> getCollection() {
        try {
            JsonNode body = rest.get().uri("/collections/{name}", collection).retrieve().body(JsonNode.class);
            JsonNode result = body == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                    : body.path("result");
            JsonNode vectors = result.path("config").path("params").path("vectors");
            int size = vectors.has("size") ? vectors.get("size").asInt() : -1;
            String distance = vectors.has("distance") ? vectors.get("distance").asText() : null;
            long points = result.path("points_count").asLong(0);
            return Optional.of(new CollectionInfo(collection, size, distance, points));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Creates the collection with cosine distance. Returns false if Qdrant reports it already exists. */
    public boolean createCollection(int vectorSize) {
        try {
            rest.put().uri("/collections/{name}", collection)
                    .body(Map.of("vectors", Map.of("size", vectorSize, "distance", "Cosine")))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException e) {
            // Concurrent creation: Qdrant answers 409 (or 400 "already exists" on some versions).
            if (getCollection().isPresent()) {
                return false;
            }
            throw e;
        }
    }

    /** Upserts one point and waits until it is applied (Story 5.3). */
    public void upsertPoint(long id, float[] vector, Map<String, Object> payload) {
        Map<String, Object> point = new java.util.LinkedHashMap<>();
        point.put("id", id);
        point.put("vector", vector);
        point.put("payload", payload);
        rest.put().uri("/collections/{name}/points?wait=true", collection)
                .body(Map.of("points", java.util.List.of(point)))
                .retrieve().toBodilessEntity();
    }

    /** Deletes one point by id and waits; deleting a missing point is a no-op in Qdrant. */
    public void deletePoint(long id) {
        rest.post().uri("/collections/{name}/points/delete?wait=true", collection)
                .body(Map.of("points", java.util.List.of(id)))
                .retrieve().toBodilessEntity();
    }

    /** One page of point ids (Story 5.4); {@code nextOffset} is null on the last page. */
    public record IdPage(java.util.List<Long> ids, Long nextOffset) { }

    /** Scrolls point ids only (no payload, no vector), starting at {@code offset} (null = first page). */
    public IdPage scrollPointIds(Long offset, int limit) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("limit", limit);
        body.put("with_payload", false);
        body.put("with_vector", false);
        if (offset != null) {
            body.put("offset", offset);
        }
        JsonNode result = rest.post().uri("/collections/{name}/points/scroll", collection)
                .body(body).retrieve().body(JsonNode.class).path("result");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode p : result.path("points")) {
            ids.add(p.path("id").asLong());
        }
        JsonNode next = result.path("next_page_offset");
        return new IdPage(ids, next.isIntegralNumber() ? next.asLong() : null);
    }

    /** Deletes several points by id and waits; missing ids are ignored by Qdrant. */
    public void deletePoints(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        rest.post().uri("/collections/{name}/points/delete?wait=true", collection)
                .body(Map.of("points", java.util.List.copyOf(ids)))
                .retrieve().toBodilessEntity();
    }

    /** The point's payload, or empty if no point has this id. */
    public Optional<JsonNode> getPointPayload(long id) {
        try {
            JsonNode body = rest.get().uri("/collections/{name}/points/{id}", collection, id)
                    .retrieve().body(JsonNode.class);
            return body == null ? Optional.empty() : Optional.of(body.path("result").path("payload"));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
