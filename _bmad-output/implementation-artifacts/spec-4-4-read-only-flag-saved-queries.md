---
title: 'Story 4.4: Read-only flag on Saved Queries'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '7dacc58'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-3-standalone-validate-endpoint.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-2-save-and-view-saved-query.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-4-search-filter-library.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-5-edit-saved-query.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A teammate can copy a saved `UPDATE` from the library thinking it is a harmless SELECT; nothing records whether a Saved Query passed the Read-Only Validator.

**Approach:** `V6__read_only.sql` adds `read_only boolean` (nullable) and `read_only_reasons text[]` (addendum §B). The single create, replace and patch service paths run `ReadOnlyValidator` whenever sql_content is supplied and store the normalized text, the flag and the reasons (FR-1, FR-5). Non-read-only SQL still saves with 201. Responses expose `read_only` and `read_only_reasons`; validator Warnings go into the mutation response `warnings[]` (addendum §B: "`warnings: []` on create and edit"). `read_only=true|false` list filter becomes active (FR-4). A startup backfill validates rows whose read_only is NULL.

## Boundaries & Constraints

**Always:** One service method per mutation; validation never blocks a save; Flyway-only schema change; `ddl-auto: validate`.

**Never:** No indexing/`indexed` flag (epic 6), no frontend or other-service change (4.5), no edit to V1–V5.

## I/O & Edge-Case Matrix

| Scenario | Input | Expected |
|----------|-------|----------|
| Create non-read-only | `UPDATE orders SET status='x'` | 201, `read_only=false`, reasons `["NON_SELECT_STATEMENT: …"]` |
| Create read-only | fenced fullwidth `ＳＥＬＥＣＴ 1` | 201, `read_only=true`, reasons `[]`, sql_content stored as `SELECT 1` |
| Create with Warning | `SELECT pg_sleep(60)` | 201, read-only, `warnings` has 1 entry; GET shows `warnings=[]` |
| Empty fence | ```` ``` ``` ```` | raw text kept, `read_only=false`, `SQL_PARSE_ERROR` |
| PATCH sql to DELETE | read-only query | `read_only=false` with reasons |
| PATCH tags only | any | flag and reasons untouched (no re-validation) |
| PUT | any sql_content | always re-validated |
| List filter | `read_only=true` / `FALSE` / `yes` | matching rows / matching rows / 400 field `read_only` |
| Backfill | rows with read_only NULL | each set; sql_content, version, updated_at unchanged; second run 0 rows |
| Migration | schema history | starts with 1..6 (no final-version assumption) |

## Decisions

- Reasons are stored as `"CODE: reason"` strings so the code is machine-matchable (`startsWith`) and the text is readable in the UI (FR-31) without a second column.
- `read_only` is nullable in the DB (NULL = not yet backfilled), exposed as `Boolean`; all API paths set it, so after startup it is never null. Reasons are `NOT NULL DEFAULT '{}'`. Index on `read_only` added for the filter.
- Backfill is an `ApplicationRunner` using JdbcTemplate, batched (500), updating only `read_only`/`read_only_reasons` with `WHERE read_only IS NULL`. It does **not** rewrite old sql_content to normalized form, and does not bump `version`/`updated_at`: a backfill is not a user edit. Idempotent, so it doubles as "one-time".
- If normalization yields an empty string (e.g. an empty fence), the raw text is stored instead so sql_content is never blank; the row is flagged `SQL_PARSE_ERROR`.
- PATCH re-validates whenever `sql_content` is present (even if unchanged), not only on a textual diff; cheap and simpler.
- Request-scoped Warnings travel on a `@Transient` entity field (`validationWarnings`) so service signatures stay unchanged; `SavedQueryResponse.fromMutation` emits them on POST/PUT/PATCH only. GET, list, deprecate and restore return `warnings: []`.
- Length limit (20,000) still applies to the raw request text, as in 4.3.

</frozen-after-approval>

## Code Map

- `sql-service/src/main/resources/db/migration/V6__read_only.sql` -- columns + index.
- `.../entity/SavedQuery.java` -- `readOnly`, `readOnlyReasons`, transient `validationWarnings`, `applyValidation`.
- `.../service/SavedQueryService.java` -- `setSqlAndValidate` in create/replace/patch; `reasons(...)`.
- `.../service/ReadOnlyBackfill.java` -- startup backfill.
- `.../service/LibraryFilter.java`, `.../repository/SavedQuerySearchImpl.java`, `.../controller/SavedQueryController.java` -- `read_only` filter.
- `.../dto/SavedQueryResponse.java` -- `read_only`, `read_only_reasons`, `fromMutation`.
- Tests: `service/SavedQueryReadOnlyServiceTest.java`, `controller/SavedQueryReadOnlyIT.java`, updated `LibraryFilterTest`, `SavedQueryControllerTest`, `SavedQueryDeprecationControllerTest`, service tests.

## Tasks & Acceptance

**Execution:**
- [x] V6 migration with nullable flag, reasons array, index.
- [x] Validate on create, PUT, and PATCH with sql_content; store normalized text, flag, reasons.
- [x] Expose fields and validator Warnings in mutation responses.
- [x] `read_only` list filter with 400 on invalid value.
- [x] Startup backfill for NULL rows.
- [x] Unit tests + Testcontainers IT for every matrix row.
- [x] Full `verify` green.

**Acceptance Criteria:**
- Given I save `UPDATE orders SET status='x'`, then 201 with `read_only=false` and reasons containing `NON_SELECT_STATEMENT`.
- Given I PATCH a read-only query's sql_content to a DELETE, then `read_only=false` with reasons; a tags-only change does not re-validate.
- Given rows saved before this story, when the service starts, each gets a read_only value, and `read_only=true|false` filters the list.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 360 tests green (304 surefire, 56 failsafe).
