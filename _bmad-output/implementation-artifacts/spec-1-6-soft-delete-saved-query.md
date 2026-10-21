---
title: 'Story 1.6: Delete a Saved Query (soft delete)'
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

**Problem:** Mistaken or obsolete queries can't be removed, and a hard delete would destroy history that later stories (deprecation, audit) rely on.

**Approach:** Add `DELETE /api/sql/queries/{id}` (FR6) backed by `V3__soft_delete.sql` (`deleted boolean NOT NULL DEFAULT false`, `deleted_at TIMESTAMPTZ`, `idx_saved_query_deleted`). The row stays; every read and edit path excludes it. The mutation is one service method (`delete`) so Epic 5.3 index sync can attach there.

## Boundaries & Constraints

**Always:** Story 1.1 error envelope; any caller may delete any query; GET by id, list/search (native SQL from 1.4, including its count), PATCH, PUT and DELETE all treat a deleted row as nonexistent (404 / absent).

**Never:** No hard delete; no restore endpoint; no deprecation (1.7) or read_only (Epic 4) logic; no edits to V1/V2.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Delete | `DELETE /{id}` on live row | 204, empty body; row kept with `deleted=true`, `deleted_at` set; GET by id → 404 | — |
| Browse/search after delete | list with no filter, `q`, `tags`, combined filters | deleted row absent; `total_elements` excludes it | — |
| Edit deleted | PATCH / PUT on deleted id | — | 404 `NOT_FOUND`; row unchanged |
| Delete twice | DELETE on deleted id | — | 404 `NOT_FOUND`; `deleted_at` unchanged |
| Unknown id | DELETE `/987654` | — | 404 `NOT_FOUND` |
| Bad id | `/abc`, overflowing number | — | 400 `VALIDATION_FAILED`, `field`=`id` |

## Decisions

- DELETE takes no body and no `version`: deletion is idempotent in intent and reversible by data, so optimistic locking isn't required. A delete racing an in-flight edit is still caught by `@Version` (409) because the soft delete is an UPDATE that bumps `version`.
- Repeat DELETE returns 404 (not 204), per the AC; the original `deleted_at` is preserved.
- Single lookup `findByIdAndDeletedFalse` replaces `findById` in `SavedQueryService.get`, so view, PATCH, PUT and DELETE share one visibility rule. The native search adds `deleted = false` unconditionally to its WHERE (shared by select and count).
- `deleted_at` uses `OffsetDateTime.now()` in the service (not a DB default) so it is set in the same flush as `deleted`.
- Entity exposes `markDeleted(at)` instead of setters; the response DTO does not expose `deleted`/`deleted_at` (deleted rows are never returned).
- Plain b-tree index on `deleted` as the epic specifies; a partial index is a possible later optimisation.
- `SavedQuerySearchIT.v2MigrationAppliedWithIndexes` changed from `containsExactly("1","2")` to `startsWith("1","2")` so later migrations don't break it (behavior unchanged).

</frozen-after-approval>

## Code Map

- `sql-service/src/main/resources/db/migration/V3__soft_delete.sql` -- new; columns + index.
- `sql-service/.../entity/SavedQuery.java` -- `deleted`, `deletedAt`, `markDeleted`.
- `sql-service/.../repository/SavedQueryRepository.java` -- `findByIdAndDeletedFalse`.
- `sql-service/.../repository/SavedQuerySearchImpl.java` -- always filter `deleted = false`.
- `sql-service/.../service/SavedQueryService.java` -- `get` uses the live-only lookup; new `delete`.
- `sql-service/.../controller/SavedQueryController.java` -- `@DeleteMapping("/{id}")` → 204.
- Tests: `SavedQueryServiceTest` (delete + 404), `SavedQueryDeleteControllerTest` (WebMvc), `SavedQueryDeleteIT` (Testcontainers `postgres:16`).

## Tasks & Acceptance

**Execution:**
- [x] V3 migration.
- [x] Entity fields and `markDeleted`.
- [x] Live-only lookup in repository and service `get`; native search excludes deleted.
- [x] Service `delete` (one method) and controller endpoint.
- [x] `SavedQueryDeleteControllerTest` -- 204 no body, 404 envelope, `abc` and overflow → 400 `field=id` without calling the service.
- [x] `SavedQueryServiceTest` -- delete marks row and saves; unknown/deleted → 404 with no save.
- [x] `SavedQueryDeleteIT` -- 204 + row kept with flags + GET 404; absent from browse/q/tags/combined search with correct totals; PATCH/PUT/DELETE on deleted → 404 and row unchanged; unknown 404, bad id 400; V3 applied with default and index.

**Acceptance Criteria:**
- Given a Saved Query, when I DELETE it, then I get 204, the row remains with deleted = true and deleted_at set, and GET by id returns 404.
- Given a deleted query, when I browse, search, PATCH, PUT, or DELETE it again, then it is absent from lists, and PATCH, PUT, and DELETE return 404.

## Implementation Notes

- `ddl-auto: validate` passes with the new columns mapped.
- `SavedQueryPersistenceIT` still uses raw `findById`; that's fine (repository-level, not an API path).

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 143 tests green (114 surefire, 29 failsafe).
