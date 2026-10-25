---
title: 'Story 5.5: Seed corpus on startup'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '7dacc58'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-3-index-on-save-keep-in-sync.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-4-reconciliation-startup-on-demand.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A fresh environment has an empty Team Library and an empty RAG Index, so the demo starts with nothing to browse and the first generation has no Examples (FR-26).

**Approach:** Ship a curated Seed Corpus (`seed/seed-queries.json`, 15 queries) over a small e-commerce schema (`seed/schema.sql`, addendum §F). On startup a `SeedCorpusSeeder` inserts every entry whose `seed_key` has no row yet, through `SavedQueryService.create` with author `seed`, so each seed is validated and indexed like any user query. If indexing fails, Reconciliation (5.4) picks the rows up. `sql.seed.enabled` turns seeding off.

## Boundaries & Constraints

**Always:** Idempotent by `seed_key`. Seeding never fails startup. Every seed passes the Read-Only Validator with no Warnings and runs without error against `seed/schema.sql`. Tests run the seed queries only on a throwaway Testcontainers PostgreSQL, never on sql_db. Tests never call real HF.

**Never:** No execution of SQL against the service's own DB. No edits to applied migrations. No frontend change. No `regions` table (region is `orders.region`).

## I/O & Edge-Case Matrix

| State | Action | After |
|-------|--------|-------|
| Fresh DB, Qdrant + embeddings up | startup | 15 rows, author `seed`, read_only, indexed |
| Restart | seed again | nothing inserted |
| User edited a seed | restart | edit kept (row exists) |
| User deleted a seed | restart | stays deleted (deleted rows still count as existing) |
| Qdrant down at startup | startup | rows inserted, indexed = false; delayed bootstrap reconciles them |
| Qdrant up, embedding fails | startup | rows inserted; Reconciliation queued |
| Two instances race on one key | unique violation | second insert skipped (logged) |
| `sql.seed.enabled=false` | startup | no seeder bean, nothing seeded |
| Seed fails the validator (corpus bug) | startup | stored, not indexed, ERROR log; the unit test prevents this |

## Decisions

- `V8__seed_key.sql`: `seed_key VARCHAR(100) UNIQUE`, nullable (addendum §B). It is set only at insert (`updatable = false`) and is not exposed in the API.
- Existence check includes soft-deleted rows. A seed is inserted once per database and never re-created or overwritten, so user edits and deletes are respected. To refresh a seed, set a new `seed_key`.
- Trigger: `ApplicationReadyEvent` (not an `ApplicationRunner`), after the read-only backfill. The seeder calls `bootstrap.tryBootstrap()` first (idempotent), so when Qdrant is up the seeds are indexed inline. Otherwise each one would log an "index not ready" Warning and wait for Reconciliation.
- Seeds go through a new `create(request, author, seedKey)` overload. The API create delegates to it with `null`. Seed rows have `is_llm_generated = false` and no prompt, notes, or dialect override.
- After seeding, if any read-only seed is unindexed and the collection is ready, `Reconciler.requestAsync()` is queued. When Qdrant is down, the delayed bootstrap's `CollectionReadyEvent` already triggers Reconciliation.
- Tests default to `sql.seed.enabled=false` via `src/test/resources/config/application.yml`, because the shared IT database must stay predictable. `SeedCorpusIT` enables it explicitly.
- Corpus content: one "last week" idiom, `o.created_at >= date_trunc('week', now()) - interval '1 week' AND o.created_at < date_trunc('week', now())`. It includes UNION ALL and CTE week-over-week variants, window functions (RANK, running SUM), `FILTER (WHERE …)`, `DISTINCT ON`, and the UX key `weekly_region_orders` / "Weekly orders by region". There is no `SELECT *`, so seeds carry no Warnings.
- The schema sample data is deterministic (`generate_series`) and relative to `now()`, so the week filters always return rows. Customers 37–40 never order (inactive-customers case).

</frozen-after-approval>

## Code Map

- `sql-service/src/main/resources/db/migration/V8__seed_key.sql` -- column.
- `sql-service/src/main/resources/seed/schema.sql`, `seed/seed-queries.json` -- the corpus.
- `.../seed/SeedEntry.java`, `SeedCorpus.java` (load + completeness check), `SeedCorpusSeeder.java`.
- `.../entity/SavedQuery.java` (`seedKey`), `.../repository/SavedQueryRepository.java` (`existsBySeedKey`), `.../service/SavedQueryService.java` (create overload).
- `application.yml` -- `sql.seed.enabled: ${SQL_SEED_ENABLED:true}`.
- Tests: `seed/SeedCorpusTest`, `seed/SeedCorpusSeederTest`, `seed/SeedSchemaIT` (throwaway Postgres, EXPLAIN ANALYZE in a read-only transaction), `seed/SeedCorpusIT`.

## Tasks & Acceptance

**Execution:**
- [x] V8 migration + entity/repository.
- [x] Seed schema and 15 seed queries.
- [x] Seeder (idempotent, toggle, Reconciliation fallback).
- [x] Unit + IT coverage (validator, schema execution, startup seeding, restart, edit/delete respected, toggle).
- [x] `mvn verify`.

**Acceptance Criteria:**
- Given a fresh environment, when the service starts, then 10–20 seed queries exist with author `seed`, complete title/description/tags, read_only = true, and indexed = true.
- Given a restart, when seeding runs again, then no duplicates are created and user edits and deletes are kept.
- Given `sql.seed.enabled=false`, nothing is seeded.
- Every seed query executes without error against `seed/schema.sql` in a Testcontainers PostgreSQL.

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- result: BUILD SUCCESS, 359 surefire + 73 failsafe tests, 0 failures.
