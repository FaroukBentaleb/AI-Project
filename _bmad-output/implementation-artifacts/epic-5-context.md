# Epic 5 Context: Team Knowledge Index (RAG Corpus)

<!-- Compiled from planning artifacts (epics-sql-service.md Epic 5, PRD FR-21..FR-26, OQ-1/OQ-3, addendum §A/§D/§E). Edit freely. -->

## Goal

Read-only, live Saved Queries are embedded into the `sql_embeddings` collection in the shared Qdrant and kept in exact sync with every change. The index is reconciled at startup and a seed corpus means it's never empty. CRUD keeps working when Qdrant or embeddings are down.

## Stories

- 5.1: Connect to Qdrant and bootstrap the collection (FR-21; compose + health group)
- 5.2: Embedding client (HF feature-extraction, `HF_EMBEDDING_MODEL`, 2 s timeout, typed "embedding unavailable")
- 5.3: Index on save and keep in sync (`V7__indexed.sql`, `shouldBeIndexed`, `syncIndex`; FR-22..FR-24)
- 5.4: Reconciliation on startup and on demand (FR-25, `POST /api/sql/admin/reindex` 202/503)
- 5.5: Seed corpus on startup (FR-26, `V8__seed_key.sql`, `sql.seed.enabled`)

## Requirements & Constraints

- sql-service owns only `sql_embeddings` (cosine, size from configuration); it never reads, modifies or deletes any other collection (commit-service owns `commit_embeddings`, 384-dim cosine).
- Qdrant call timeout 1 s; embedding timeout 2 s, no retries. Qdrant unreachable at startup: service starts, CRUD works, bootstrap retried every 30 s, then Reconciliation (5.4).
- Indexing rule: `shouldBeIndexed(q) = read_only && !deleted && !deprecated`; point id = Saved Query id; payload `title, description, sql_content, original_nl_prompt, tags`; embedded text `title \n description \n sql_content [\n original_nl_prompt]`.
- Index/embedding failure never fails a mutation: DB change kept, `indexed = false`, Warning in the response.
- Health: `dependencies` group = qdrant (+ llm when it exists); the default `/actuator/health` does not include dependency indicators, and health never makes live LLM calls.
- Embedding model and dimension are open (OQ-1/OQ-3): everything is config-driven.

## Technical Decisions

- Qdrant accessed over REST (port 6333) with Spring `RestClient` + `JdkClientHttpRequestFactory` — no Qdrant/gRPC client dependency. Package `com.devassisthub.sql.rag`.
- Config keys: `qdrant.host`, `qdrant.port` (gRPC 6334, reserved), `qdrant.rest-port` (6333), `qdrant.timeout: 1s`, `qdrant.collection: sql_embeddings`, `qdrant.bootstrap-retry: PT30S`, `hf.embedding-model`, `hf.embedding-dimension: 384`.
- Compose and tests both pin `qdrant/qdrant:v1.13.2`.
- Schema changes only via new Flyway migrations (V7+); `ddl-auto: validate`; Testcontainers 1.21.4; shared `AbstractIntegrationTest` (shared Qdrant across ITs — do not assume an empty collection).
