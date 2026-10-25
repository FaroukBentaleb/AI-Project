package com.devassisthub.sql.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.qdrant.QdrantContainer;

/**
 * Base for every {@code *IT}: one PostgreSQL and one Qdrant container per JVM (test run),
 * started on class load and shared by all subclasses; Ryuk removes them when the JVM exits.
 * Unit tests must not extend this class.
 */
public abstract class AbstractIntegrationTest {

    /** Pinned; keep in sync with docker-compose.yml. */
    public static final String POSTGRES_IMAGE = "postgres:16";
    /** Pinned (compose uses latest for dev convenience; tests must be reproducible). */
    public static final String QDRANT_IMAGE = "qdrant/qdrant:v1.13.2";

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);
    public static final QdrantContainer QDRANT = new QdrantContainer(QDRANT_IMAGE);

    static {
        POSTGRES.start();
        QDRANT.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("qdrant.host", QDRANT::getHost);
        registry.add("qdrant.port", () -> QDRANT.getMappedPort(6334));
        registry.add("qdrant.rest-port", () -> QDRANT.getMappedPort(6333));
        // Story 5.3: indexing embeds through a local stub, never the real Hugging Face API.
        registry.add("hf.embedding-api-token", () -> "test-token");
        registry.add("groq.api-key", () -> "test-token");
        registry.add("hf.embedding-model", () -> "test/stub-model");
        registry.add("hf.embedding-base-url", StubEmbeddingServer::baseUrl);
    }
}
