---
title: 'Story 1.8: Record usage of a Saved Query'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '504d215'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Teammates can't tell which library queries are actually used and trusted.

**Approach:** Add `POST /api/sql/queries/{id}/usage` (FR8) backed by `V5__usage.sql` (`use_count bigint NOT NULL DEFAULT 0`, `last_used_at TIMESTAMPTZ`). The increment is one atomic SQL `UPDATE ... SET use_count = use_count + 1` so concurrent calls never lose counts. Both fields appear in the response and join the sort whitelist. An integration test proves NFR2 (reads p95 < 300 ms) on a seed-sized dataset.

## Boundaries & Constraints

**Always:** Story 1.1 error envelope; any caller may record usage; deleted rows are invisible (404); deprecated rows can still be used.

**Never:** No edits to V1–V4; usage never bumps `version` or `updated_at`; no change to 1.2–1.7 behavior except the added response fields and sort keys.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| New query | POST create, GET | `use_count=0`, `last_used_at=null` | — |
| Record twice | `POST /{id}/usage` x2 | each 204 empty body; `use_count=2`; `last_used_at` = 2nd call; version/updated_at unchanged | — |
| Concurrent | 40 parallel POSTs | all 204; `use_count=40` | — |
| Deprecated row | POST usage | 204, counted | — |
| Request body | any/no body | ignored | — |
| Unknown / deleted | POST on unknown or soft-deleted id | — | 404 `NOT_FOUND`; deleted row's count unchanged |
| Bad id | `/abc/usage`, overflow | — | 400 `VALIDATION_FAILED`, `field`=`id` |
| Sort | `sort=use_count[,asc|desc]`, `sort=last_used_at[,asc|desc]` | ordered, id tie-breaker; never-used rows last in both directions | other fields still 400 `field`=`sort` |
| NFR2 | 500 seeded rows; view, browse, search | p95 < 300 ms each | — |

## Decisions

- Atomic JPQL bulk `UPDATE` (`@Modifying`, `WHERE id = :id AND deleted = false`); 0 rows updated -> 404. No read-then-write, no optimistic lock: the row lock serialises increments, so no retries or 409s are possible.
- Usage is not an edit: the bulk update bypasses `@Version` and `@UpdateTimestamp`, so a copy never makes an editor's `version` stale.
- Entity maps both columns `insertable=false, updatable=false`; only the repository UPDATE writes them (DB default 0 on insert).
- `use_count` is `bigint`/`long` (no overflow concern).
- `last_used_at` sort uses `NULLS LAST` in both directions (never-used queries are least interesting either way).
- Deprecated queries still count (they remain visible and copyable per 1.7).
- No index on `use_count`/`last_used_at` (small table; perf test passes without it).
- Perf IT: 500 rows seeded via JDBC, 10 warm-up + 50 timed HTTP calls per endpoint, nearest-rank p95. Browse covers default and `use_count` sort; search combines q/tags/author/llm_generated/deprecated.
- Tests that previously asserted `use_count`/`last_used_at` were rejected as sort keys (as the 1.3 AC anticipated) now use other invalid keys.
- Migration-history assertion uses `startsWith("1","2","3","4","5")`.

</frozen-after-approval>

## Code Map

- `sql-service/src/main/resources/db/migration/V5__usage.sql` -- new.
- `.../entity/SavedQuery.java` -- read-only `useCount`, `lastUsedAt`.
- `.../dto/SavedQueryResponse.java` -- two new fields.
- `.../repository/SavedQueryRepository.java` -- `recordUsage` atomic update.
- `.../repository/SavedQuerySearchImpl.java` -- column map + `NULLS LAST`.
- `.../service/LibraryPaging.java` -- whitelist.
- `.../service/SavedQueryService.java` -- `recordUsage`.
- `.../controller/SavedQueryController.java` -- endpoint.
- Tests: `SavedQueryUsageServiceTest`, `LibraryPagingTest`, `SavedQueryControllerTest`/`SavedQueryListIT` (sort keys), `SavedQueryUsageControllerTest` (WebMvc), `SavedQueryUsageIT`, `SavedQueryReadPerformanceIT` (Testcontainers `postgres:16`).

## Tasks & Acceptance

**Execution:**
- [x] V5 migration.
- [x] Entity fields, response fields.
- [x] Repository atomic update; service `recordUsage`; controller endpoint.
- [x] Sort whitelist + native ORDER BY mapping.
- [x] Unit tests: service, LibraryPaging, WebMvc controller.
- [x] `SavedQueryUsageIT` -- every matrix row against PostgreSQL, incl. concurrency and V5 columns.
- [x] `SavedQueryReadPerformanceIT` -- NFR2 p95 for view, browse, search.

**Acceptance Criteria:**
- Given a Saved Query with use_count 0, when I POST usage twice, then each call returns 204, use_count is 2, and last_used_at is the latest call.
- Given an unknown or deleted id, when I POST usage, then I get 404 `NOT_FOUND`.
- Sorting by use_count and last_used_at works.
- Reads (view, browse, search) meet p95 < 300 ms with a seed-sized dataset, measured in an integration test.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 188 tests green (142 surefire, 46 failsafe).
