---
title: 'Story 5.3: Index on save and keep the index in sync'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '7dacc58'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-1-qdrant-collection-bootstrap.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-2-embedding-client.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Saved read-only queries must teach the generator immediately, and withdrawn ones must stop influencing it (FR-22..FR-24, indexing parts of FR-1, FR-5, FR-6, FR-7).

**Approach:** `V7__indexed.sql` adds `indexed`. `QueryIndexer` holds the single rule `shouldBeIndexed(q) = read_only && !deleted && !deprecated` and the single hook `syncIndex(q)`: upsert (embed `title \n description \n sql_content [\n original_nl_prompt]`, point id = Saved Query id, payload `title, description, sql_content, original_nl_prompt, tags`) or delete the point, then store `indexed`. Create, replace, patch, delete, deprecate and restore call it synchronously before responding.

## Boundaries & Constraints

**Always:** Indexing failure never fails the mutation: DB change kept, `indexed = false`, Warning in `warnings[]`. Only `sql_embeddings` is touched. Tests never call real HF.

**Never:** No reconciliation or backfill of existing rows (5.4); no seed (5.5); no frontend change; no edit of applied migrations.

## I/O & Edge-Case Matrix

| Mutation | Query state after | Qdrant | indexed | Warning |
|----------|-------------------|--------|---------|---------|
| Create SELECT | read-only, live | upsert | true | — |
| Create UPDATE | not read-only | delete (no-op) | false | — |
| Edit indexed field, still qualifies | — | re-embed + upsert | true | — |
| Edit SQL into DELETE | not read-only | delete | false | — |
| Edit description of deprecated | deprecated | delete (no-op) | false | — |
| Edit notes/dialect only, flag in step | — | no call | unchanged | — |
| Delete / deprecate | — | delete | false | — |
| Restore | qualifies | upsert | true | — |
| Embedding unavailable / Qdrant error / collection not bootstrapped | qualifies | — | false | `Not yet indexed: team examples unavailable (<reason>).` |
| Point removal fails | — | — | false | `Not yet removed from team examples: …` |

## Decisions

- `QueryIndexer` lives in `rag`; `SavedQueryService` gets it as a third constructor argument and calls it after `saveAndFlush` (id known), inside the same transaction. A rare commit failure after a successful upsert leaves an orphan point that Reconciliation (5.4) removes.
- `indexed` is mapped `insertable/updatable = false` and written only by `SavedQueryRepository.setIndexed` (JPQL bulk update): indexing is not an edit, so it never bumps `version` or `updated_at` (create still returns `version = 0`).
- Edits call `syncIndex` only if an embedded/payload field (title, description, sql_content, original_nl_prompt, tags) or `read_only` changed, or if `shouldBeIndexed` disagrees with the stored `indexed` (lets any edit heal an earlier failure). Deprecate, restore (when it actually restores) and delete always sync.
- Any failure — including a failed point removal — stores `indexed = false` (story wording); an orphan point is Reconciliation's job. Delete answers 204 without a body, so its warning is only logged.
- When the collection is not yet bootstrapped, the embedding call is skipped (fails fast, no 2 s wait).
- Warning texts start with `QueryIndexer.WARNING_PREFIX` / `REMOVE_WARNING`; the UI toast keys only on `warnings[]` being non-empty (EXPERIENCE.md), so text is informational. Deprecate/restore responses now also carry `warnings[]` (`fromMutation`).
- `indexed` added to `SavedQueryResponse` (GET included), after `read_only_reasons`.
- `read_only = null` (not yet backfilled) does not qualify.
- Tests: `StubEmbeddingServer` (static MockWebServer, deterministic 384-dim vector, switchable delay/failure) wired into `AbstractIntegrationTest` via `hf.*` properties; `QdrantDownIT` points `hf.embedding-base-url` at a dead port. "Qdrant stopped" is covered by `QdrantDownIT` (201, indexed=false, Warning) and unit tests for Qdrant errors after bootstrap, since the shared Qdrant cannot be stopped.

</frozen-after-approval>

## Code Map

- `sql-service/src/main/resources/db/migration/V7__indexed.sql` -- `indexed BOOLEAN NOT NULL DEFAULT FALSE`.
- `.../rag/QueryIndexer.java` -- rule, embedded text, payload, `syncIndex`.
- `.../rag/QdrantClient.java` -- `upsertPoint`, `deletePoint`, `getPointPayload` (all `wait=true`).
- `.../service/SavedQueryService.java` -- hook in all six mutations; edit change detection.
- `.../entity/SavedQuery.java` (`indexed`, `addWarning`), `.../repository/SavedQueryRepository.java` (`setIndexed`), `.../dto/SavedQueryResponse.java` (`indexed`), `.../controller/SavedQueryController.java` (deprecate/restore warnings).
- Tests: `rag/QueryIndexerTest` (8), `rag/IndexSyncIT` (7), `support/StubEmbeddingServer`, `QdrantDownIT` extended; service unit tests take a mocked indexer.

## Tasks & Acceptance

**Execution:**
- [x] V7 migration + entity/repository/DTO.
- [x] Qdrant point operations.
- [x] `QueryIndexer` with typed-failure handling and warnings.
- [x] Wire `syncIndex` into create, replace, patch, delete, deprecate, restore.
- [x] Shared embedding stub for ITs; unit + IT coverage incl. NFR2 latency.
- [x] `mvn verify`.

**Acceptance Criteria:**
- Given a read-only save, when 201 returns, then exactly one point with id = query id exists and indexed = true.
- Given an UPDATE save, then no point exists and indexed = false.
- Given an indexed query, deprecate / delete / edit SQL into DELETE removes the point; restore re-indexes; editing a deprecated query's description does not re-index.
- Given Qdrant stopped, a read-only save returns 201 with indexed = false and a Warning.
- Create and edit p95 < 3 s with 1 s mocked embedding latency.

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- result: BUILD SUCCESS, 338 surefire + 67 failsafe tests, 0 failures.
