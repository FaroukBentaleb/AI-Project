---
title: 'Story 1.5: Edit a Saved Query safely'
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

**Problem:** Saved Queries can be created and found but not corrected; a naive edit could wipe fields or silently overwrite a teammate's concurrent change.

**Approach:** Add `PATCH /api/sql/queries/{id}` (only supplied fields change) and `PUT /api/sql/queries/{id}` (full replace, create's validation) (FR5). Both require the client's `version`; a mismatch returns 409 `CONFLICT` before anything is written, and `@Version` catches races between check and flush. Each mutation is one service method (`replace`, `patch`) so Epic 4.4 validation and Epic 5.3 indexing can attach there.

## Boundaries & Constraints

**Always:** Story 1.1 error envelope (`field` snake_case); response = Story 1.2 shape with `warnings: []`; any caller may edit any query; `author`, `created_at`, `id` never change; `updated_at` advances on a real change.

**Never:** No migration (V1 already has `version`). No deleted (1.6), deprecated (1.7), read_only (Epic 4) logic. No change to POST/GET/list.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| PATCH tags only | `{"tags":[..],"version":0}` | 200; only tags change; version 0→1; updated_at advances; created_at/author same | — |
| PUT full replace | all create fields + `version`; body also carries `author`/`created_at`/`id` | 200; omitted optionals reset (tags `[]`, notes/prompt null, dialect `postgresql`, llm false); server-managed fields ignored | — |
| PUT invalid | missing/blank description, title, sql_content; blank tag; missing `version` | — | 400 `VALIDATION_FAILED`, `field` set; nothing changes |
| PATCH invalid | blank title/description/sql_content/tag; over-limit field; >10 tags; missing or non-numeric `version`; malformed JSON | — | 400 `VALIDATION_FAILED`; nothing changes |
| Stale version | `version` ≠ current (behind or ahead) on PATCH or PUT | — | 409 `CONFLICT`, `field`=`version`; row unchanged |
| Lost race | concurrent flush after check | — | `ObjectOptimisticLockingFailureException` → 409 `CONFLICT` |
| Unknown id | PATCH/PUT `/987654` | — | 404 `NOT_FOUND` |
| Bad id | `/abc` | — | 400, `field`=`id` |

## Decisions

- `version` is required in the body of both PATCH and PUT (no `If-Match` header); missing → 400. An optional version would make "safe" edits opt-in.
- PATCH: absent **or null** means "leave unchanged" (records can't distinguish them). Required-on-create text fields, if supplied, must be non-blank (`@Pattern(".*\S.*")`). Optional text fields are cleared with `""` via PATCH, or by omitting them in PUT. JSON Merge Patch null-clears is not supported.
- PATCH `dialect: ""` resets to `postgresql`, same as create.
- Version check is done explicitly in the service before mutating (deterministic 409, no write); the `@Version` column still guards the race. Both map to the same 409 envelope.
- A PATCH that changes nothing (only `version`) returns 200 with the row as-is; Hibernate issues no UPDATE so version/updated_at stay put.
- Edit ITs use `java.net.http.HttpClient` because the default `TestRestTemplate` request factory can't send PATCH; no new dependency.

</frozen-after-approval>

## Code Map

- `sql-service/.../dto/ReplaceSavedQueryRequest.java` -- new; create's constraints + `@NotNull version`.
- `sql-service/.../dto/PatchSavedQueryRequest.java` -- new; all optional with limits, non-blank when supplied, `@NotNull version`.
- `sql-service/.../service/SavedQueryService.java` -- `replace`, `patch`, private `lockedForEdit`, `staleVersion`.
- `sql-service/.../controller/SavedQueryController.java` -- `@PutMapping`, `@PatchMapping` on `/{id}`.
- `sql-service/.../exception/GlobalExceptionHandler.java` -- `ObjectOptimisticLockingFailureException` → 409 `CONFLICT`.
- Tests: `SavedQueryEditControllerTest` (WebMvc), `SavedQueryEditIT` (Testcontainers `postgres:16`).

## Tasks & Acceptance

**Execution:**
- [x] Request DTOs for PUT and PATCH.
- [x] Service `replace`/`patch` with version check; one method per mutation.
- [x] Controller endpoints.
- [x] 409 mapping for optimistic-lock failures.
- [x] `SavedQueryEditControllerTest` -- pass-through, every PUT/PATCH 400 row without calling the service, bad id, stale 409, race 409, 404.
- [x] `SavedQueryEditIT` -- PATCH tags only, PUT replace keeps author/created_at, PUT missing description 400 unchanged, stale PATCH/PUT 409 unchanged, unknown id 404, PATCH blank field 400 unchanged.

**Acceptance Criteria:**
- Given a Saved Query, when I PATCH only `tags`, then other fields are unchanged and updated_at advances; a PUT missing description is 400 `VALIDATION_FAILED`; a stale `version` on PATCH or PUT is 409 `CONFLICT` and nothing changes; author and created_at never change; an unknown id is 404.

## Implementation Notes

- No schema change required; `ddl-auto: validate` passes unchanged.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 132 tests green (108 surefire, 24 failsafe).
