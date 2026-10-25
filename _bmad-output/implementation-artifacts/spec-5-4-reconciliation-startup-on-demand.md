---
title: 'Story 5.4: Reconciliation on startup and on demand'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '7dacc58'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-1-qdrant-collection-bootstrap.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-3-index-on-save-keep-in-sync.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 5.3 leaves gaps it cannot close synchronously: rows saved while Qdrant or embeddings were down (`indexed = false`), rows that pre-date V7, orphan points after a failed removal or a rolled-back commit, and a collection bootstrapped late. The RAG Index must heal itself (FR-25).

**Approach:** A `Reconciler` (package `rag`) brings `sql_embeddings` into exact agreement with `shouldBeIndexed`: it scrolls all point ids and deletes points whose id has no qualifying row; then walks rows in id batches and calls `QueryIndexer.syncIndex` for every qualifying row that is `indexed = false` or has no point, and for every non-qualifying row still flagged `indexed = true`. It runs asynchronously when the collection becomes ready (startup and delayed bootstrap) and on `POST /api/sql/admin/reindex` (202, or 503 when Qdrant is unreachable).

## Boundaries & Constraints

**Always:** Never blocks startup or CRUD requests; bounded batches; a per-row failure leaves `indexed = false` and is retried by the next run; only `sql_embeddings` is touched; tests never call real HF.

**Never:** No seed (5.5); no new migration; no frontend change; no periodic schedule beyond the triggers above.

## I/O & Edge-Case Matrix

| State before run | Action | After |
|------------------|--------|-------|
| Qualifying, indexed = false | embed + upsert | point, indexed = true |
| Qualifying, indexed = true, point missing | embed + upsert | point, indexed = true |
| Qualifying, indexed = true, point present | none | unchanged |
| Point id with no row / deleted / deprecated / not read-only | delete point | no point |
| Non-qualifying, indexed = true | flag corrected | indexed = false |
| Embedding fails for a row | — | indexed = false, retried next run |
| 3 consecutive row failures | run stops early (logged) | rest left for next run |
| Collection not ready / scroll fails | run skipped/aborted (WARN) | next trigger retries |
| Run requested while one is running | one extra run queued (coalesced) | — |
| `POST /admin/reindex`, Qdrant up | run queued | 202 `{"status":"accepted"}` |
| `POST /admin/reindex`, Qdrant down | — | 503 `RAG_INDEX_UNAVAILABLE`, "RAG index unavailable: …" |

## Decisions

- Trigger: `CollectionBootstrap` publishes `CollectionReadyEvent` exactly once, on the false→true transition (startup or a delayed retry); `Reconciler` listens and calls `requestAsync()`. Startup runs after `ApplicationRunner`s, so the read-only backfill (4.4) has already set `read_only` on old rows.
- Async: a dedicated single-thread daemon executor in `Reconciler`; a pending flag coalesces requests (at most one queued run behind the running one). `reconcile()` is also public and synchronous for tests.
- Orphans first (scroll `with_payload/with_vector = false`, page = batch size), then rows. Point ids are collected into a set to detect qualifying rows whose point is missing (corpus is small; documented scaling limit).
- Race with a concurrent create (point upserted before the row commits): orphan candidates with id greater than `max(id)` read before the scroll are skipped. A rarer race that removes a fresh point is healed by the next run ("point missing").
- Each row is re-read in its own transaction with `PESSIMISTIC_WRITE` (`findForIndexing`) before `syncIndex`, so a concurrent edit of the same row waits at most one embed + upsert (≤ ~3 s) instead of racing a stale payload. Other rows are never locked.
- Early stop after 3 consecutive per-row failures (embedding outage) so a run cannot spend 2 s × N rows timing out.
- Endpoint `AdminController` `POST /api/sql/admin/reindex`: calls `bootstrap.tryBootstrap()` then a live `getCollection()`; failure → 503 with new error code `RAG_INDEX_UNAVAILABLE` (the PRD lists no index-specific code; `LLM_*` would be misleading). No auth, like every other endpoint (team model).
- Config: `sql.reconcile.batch-size: 100`.

</frozen-after-approval>

## Code Map

- `.../rag/Reconciler.java` -- algorithm, async trigger, event listener.
- `.../rag/CollectionReadyEvent.java`, `.../rag/CollectionBootstrap.java` -- publishes the event on first readiness.
- `.../rag/QdrantClient.java` -- `scrollPointIds`, `deletePoints`.
- `.../repository/SavedQueryRepository.java` -- `findForIndexing` (locked), `findIndexStateAfter`, `maxId`.
- `.../controller/AdminController.java` -- `POST /api/sql/admin/reindex`.
- `.../config/QdrantConfig.java`, `application.yml` -- wiring, `sql.reconcile.batch-size`.
- Tests: `rag/ReconcilerTest`, `rag/CollectionBootstrapTest` (event), `controller/AdminControllerTest`, `rag/ReconcileIT`, `rag/QdrantDownIT` (delayed bootstrap reconciles, 503).

## Tasks & Acceptance

**Execution:**
- [x] Qdrant scroll + batch delete.
- [x] Repository queries.
- [x] `Reconciler` + `CollectionReadyEvent`.
- [x] Admin endpoint with 202/503.
- [x] Unit + IT coverage.
- [x] `mvn verify`.

**Acceptance Criteria:**
- Given three qualifying queries with indexed = false and one orphan point, when reconciliation runs (as on restart with Qdrant up), then all three are indexed and the orphan is removed.
- Given Qdrant comes up after a startup outage, when the delayed bootstrap succeeds, then reconciliation runs automatically.
- Given `POST /api/sql/admin/reindex`, when Qdrant is up it returns 202 and reconciles; when down it returns 503 in the standard envelope with a "RAG index unavailable" message.

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- result: BUILD SUCCESS, 348 surefire + 70 failsafe tests, 0 failures.
