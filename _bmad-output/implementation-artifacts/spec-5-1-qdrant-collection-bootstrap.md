---
title: 'Story 5.1: Connect to Qdrant and bootstrap the collection'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '7dacc58'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-2-qdrant-testcontainer.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** RAG needs a home: there is no Qdrant connection, no `sql_embeddings` collection, and no way to see Qdrant status — and a Qdrant outage must not take sql-service down.

**Approach:** A small REST `QdrantClient` (RestClient, 1 s timeout) and a `CollectionBootstrap` that, on `ApplicationReadyEvent` and then every `qdrant.bootstrap-retry` (30 s) until it succeeds, creates `sql_embeddings` (cosine, size `hf.embedding-dimension`) if missing (FR-21). A `qdrant` health indicator lives only in the `dependencies` health group. Compose gets the §E sql-service env, a working Qdrant healthcheck and a pinned image.

## Boundaries & Constraints

**Always:** Only `sql_embeddings` is ever created/read; no delete or update calls to any collection; startup never blocks on or fails because of Qdrant; config-driven dimension.

**Never:** No embeddings, upserts, `indexed` column or reconciliation (5.2–5.4); no Qdrant Java/gRPC client; no frontend change; no other compose service block touched besides qdrant and sql-service.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Empty Qdrant | no `sql_embeddings` | created: size = config, distance Cosine | — |
| Restart | collection with points | untouched (no recreate), points kept | — |
| Other collection | e.g. `commit_embeddings` | untouched | — |
| Existing, wrong size | size ≠ config | not modified; ERROR log; health detail `vector_size_mismatch` | bootstrap counts as done |
| Create race | PUT returns 409 | treated as exists | — |
| Qdrant down at start | connection refused / timeout | app starts, `/actuator/health` UP, `/actuator/health/dependencies` DOWN with qdrant DOWN | WARN log, retry every 30 s |
| Qdrant comes up | after outage | next retry creates the collection, retries stop | — |

## Decisions

- REST on 6333 rather than gRPC (addendum allows either): no new dependency, `RestClient` + `JdkClientHttpRequestFactory` with connect/read timeout `qdrant.timeout` (1 s).
- Final property names: `qdrant.host` (`QDRANT_HOST`, default localhost), `qdrant.port` (`QDRANT_PORT`, gRPC 6334 — bound and reserved, unused by REST), `qdrant.rest-port` (`QDRANT_REST_PORT`, 6333), `qdrant.timeout` (1s), `qdrant.collection` (sql_embeddings), `qdrant.bootstrap-retry` (`PT30S`; ISO-8601 because `@Scheduled` does not parse `30s`). Compose sets `QDRANT_REST_PORT: 6333` alongside the §E keys.
- Dimension key `hf.embedding-dimension` (`HF_EMBEDDING_DIMENSION`), default **384** (MiniLM-class, matches commit-service; OQ-1/OQ-3 still open). Lives under `hf.` because Story 5.2 checks embedding output against it.
- A size mismatch on an existing collection is reported, never fixed automatically (recreating would destroy points); changing the model needs a manual drop + reindex.
- Health: indicator `qdrant` calls `GET /collections/{name}` (no LLM, cheap): UP with `collection`, `vector_size`; DOWN with error. Group `dependencies` includes `qdrant` only — `llm` is added with the LLM client (Boot validates group membership, so naming a missing contributor would fail startup). A `HealthEndpointGroupsPostProcessor` removes `dependencies` members from the default (root) group, so Qdrant DOWN never makes `/actuator/health` DOWN; the other existing root contributors are left as they are (not narrowed to db only, to avoid changing current behavior).
- Retry via `@Scheduled(fixedDelayString = qdrant.bootstrap-retry)` guarded by an `AtomicBoolean ready`; `isReady()` exposed for 5.3/5.4. Hook for "run reconciliation after a delayed bootstrap" is left to 5.4.
- Compose: Qdrant image pinned to `qdrant/qdrant:v1.13.2` (same as tests; existing volume has no collections, so the downgrade from latest/v1.19.2 is safe). Healthcheck uses bash `/dev/tcp` against `/readyz` because the image ships no curl/wget (root cause of `unhealthy`). sql-service gets `QDRANT_HOST`, `QDRANT_PORT`, `QDRANT_REST_PORT`, `HF_EMBEDDING_MODEL`, `HF_RETRY_MAX_ATTEMPTS: 1`, `depends_on: qdrant: service_healthy`; `.env.example` gets `HF_EMBEDDING_MODEL=`. `hf.embedding-model` key added now (consumed in 5.2).

</frozen-after-approval>

## Code Map

- `sql-service/src/main/java/com/devassisthub/sql/rag/QdrantProperties.java` -- bound config.
- `.../rag/QdrantClient.java` -- REST calls (get/create collection).
- `.../rag/CollectionBootstrap.java` -- startup + scheduled retry.
- `.../rag/QdrantHealthIndicator.java`, `.../config/HealthGroupsConfig.java` -- health.
- `.../config/QdrantConfig.java` -- RestClient bean, `@EnableScheduling`.
- `application.yml`, `docker-compose.yml`, `.env.example`, `docs/PROJECT_CONTEXT.md`.
- Tests: `rag/CollectionBootstrapTest`, `rag/QdrantHealthIndicatorTest`, `rag/CollectionBootstrapIT`, `rag/QdrantDownIT`.

## Tasks & Acceptance

**Execution:**
- [x] Config keys + properties + RestClient.
- [x] QdrantClient + CollectionBootstrap with retry.
- [x] Health indicator, `dependencies` group, root exclusion.
- [x] Unit tests and ITs (bootstrap, restart, other collection untouched, down-then-up).
- [x] Compose, `.env.example`, PROJECT_CONTEXT note.
- [x] `mvn verify` + compose check.

**Acceptance Criteria:**
- Given an empty Qdrant, when the service starts, then `sql_embeddings` exists with configured size and cosine; a restart leaves it and its points intact.
- Given another collection exists, when the service starts, then it is untouched.
- Given Qdrant is down at startup, when the service starts, then it boots, `/actuator/health` is UP, `/actuator/health/dependencies` shows qdrant DOWN, and the bootstrap succeeds within 30 s of Qdrant coming up.

## Spec Change Log

- `qdrant.bootstrap-retry` default written as `PT30S` (Spring 6.1 `@Scheduled` rejects `30s`).
- `QdrantDownIT` does not extend `AbstractIntegrationTest` (its `@DynamicPropertySource` cannot reliably override the base's `qdrant.*`); it reuses the shared PostgreSQL, so `AbstractIntegrationTest.POSTGRES` is now `public`.

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- result: BUILD SUCCESS, 313 surefire + 60 failsafe tests green.
- `docker compose up -d --build qdrant sql-service` -- result: qdrant v1.13.2 `healthy` (existing volume had no collections; started fine), sql-service `healthy`; `/actuator/health` UP, `/actuator/health/dependencies` qdrant UP (sql_embeddings, 384, Cosine).

**Manual checks:**
- Risk: user `.env` has no `HF_EMBEDDING_MODEL` (compose warns, blank default; harmless until 5.2).
- Root health still includes ping/diskSpace/discovery (not narrowed to db only).
