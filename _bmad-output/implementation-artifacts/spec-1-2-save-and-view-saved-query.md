---
title: 'Story 1.2: Save and view a Saved Query'
type: 'feature'
created: '2026-10-18'
status: 'done'
baseline_commit: '0c9b23f68c6e3988d517f45e835bf51d83240805'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Team Library has a table and entity but no API: a developer cannot save a working query or open it again.

**Approach:** Add `POST /api/sql/queries` (FR-1 core) and `GET /api/sql/queries/{id}` (FR-2) with request DTOs, Bean Validation for the PRD length limits, author from `CallerIdentity`, and a response DTO. No validator, indexing, or soft-delete logic yet.

## Boundaries & Constraints

**Always:** All errors use the Story 1.1 envelope and `GlobalExceptionHandler`; `field` is the snake_case name. Author and timestamps are server-set; client-supplied `id`, `author`, `created_at`, `updated_at`, `version` are ignored. Create lives in one service method (future `validate`/`syncIndex` hooks). Never commit.

**Never:** No list/search/edit/delete endpoints. No `read_only`, `indexed`, `deleted` fields or logic (later stories/epics). No new migration.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create, full body | title, description, sql_content + optional tags, notes, dialect, is_llm_generated, original_nl_prompt | 201, full Saved Query (id, author, created_at, updated_at, version, all fields, `warnings: []`), `Location: /api/sql/queries/{id}` | — |
| Create, minimal | only the three required fields | 201; tags `[]`, dialect `postgresql`, is_llm_generated `false`, notes/original_nl_prompt null | — |
| Required missing/blank | title, description or sql_content absent, `""` or `"  "` | 400 `VALIDATION_FAILED`, `field` names it | — |
| Length limit | title > 200, description > 2000, sql_content > 20000, > 10 tags, tag > 50, blank tag | 400 `VALIDATION_FAILED`, `field` names it (`tags` for tag errors) | — |
| No identity | no / blank `X-User-Id` | author `anonymous` | — |
| Identity too long | `X-User-Id` > 100 chars after trim | 400 `VALIDATION_FAILED`, `field: "x_user_id"`, nothing saved | — |
| Dialect | any non-blank value ≤ 20 chars | stored as-is; > 20 chars → 400 `VALIDATION_FAILED` field `dialect` | — |
| View existing | known id | 200, all attributes | — |
| View unknown | unknown id; non-numeric id | 404 `NOT_FOUND`; 400 `VALIDATION_FAILED` | — |

## Decisions

- `X-User-Id` longer than 100 characters is rejected with 400 (not truncated).
- `dialect` accepts any value up to 20 characters; absent or null defaults to `postgresql`.

</frozen-after-approval>

## Code Map

- `sql-service/.../entity/SavedQuery.java` -- entity from 1.1; setters exist for all writable fields; `author` not updatable; `setTags(null)` → empty list.
- `sql-service/.../repository/SavedQueryRepository.java` -- `JpaRepository<SavedQuery, Long>`; reuse.
- `sql-service/.../controller/CallerIdentity.java` -- request-scoped; `getUserId()` returns trimmed header or `anonymous`.
- `sql-service/.../exception/{ApiException,GlobalExceptionHandler,ErrorResponse}.java` -- throw `ApiException(NOT_FOUND, "NOT_FOUND", ...)` for 404; validation and type-mismatch already map to 400 with snake_case field.
- `sql-service/.../dto/`, `service/` -- empty packages; new classes go here.
- Tests: Story 1.1 pattern — `@WebMvcTest` for controllers, Testcontainers `postgres:16` with `@ServiceConnection` for persistence (`*IT`, failsafe). Testcontainers pinned 1.21.4 in pom; do not change.
- Toolchain: `JAVA_HOME=C:\Program Files\Java\jdk-21`, Maven `C:\Program Files\JetBrains\ideaIU-2024.3.5.win\plugins\maven\lib\maven3\bin\mvn`.

## Tasks & Acceptance

**Execution:**
- [x] `dto/CreateSavedQueryRequest.java` -- record with `@NotBlank`/`@Size` per matrix; `tags` `@Size(max=10)` with element `@NotBlank @Size(max=50)`.
- [x] `dto/SavedQueryResponse.java` -- record of every entity field plus `warnings` (`List<String>`, empty on create; omitted-free: always present); static `from(SavedQuery, List<String>)`.
- [x] `service/SavedQueryService.java` -- `create(request, author)` and `get(id)`; `get` throws `ApiException` 404 `NOT_FOUND`; defaults applied (dialect `postgresql`, is_llm_generated `false`).
- [x] `controller/SavedQueryController.java` -- `@RequestMapping("/api/sql/queries")`; POST `@Valid` → 201 + `Location`; GET `/{id}` → 200.
- [x] `controller/SavedQueryController.java` (or service) -- reject author > 100 chars with `ApiException` 400 `VALIDATION_FAILED`, field `x_user_id`; `dialect` `@Size(max=20)`, null → `postgresql`.
- [x] `src/test/.../SavedQueryServiceTest.java` -- success, defaults, author passthrough, 404.
- [x] `src/test/.../SavedQueryControllerTest.java` -- `@WebMvcTest`: every matrix row's HTTP shape incl. each validation field and `anonymous`.
- [x] `src/test/.../SavedQueryApiIT.java` -- `@SpringBootTest` + Testcontainers: POST then GET round-trip, 404 for unknown id; Eureka disabled in test.

**Acceptance Criteria:**
- Given the running stack, when I POST a valid body to `/api/sql/queries` and GET the returned id, then both return the same Saved Query in snake_case JSON.

## Implementation Notes

## Spec Change Log

## Review Triage Log

| # | Source | Finding | Verdict | Evidence / route |
|---|---|---|---|---|
| 1 | blind, edge | Blank `dialect` (`""`, `"  "`) is stored instead of defaulting | low | `create` only defaults on null; Decision says absent/null → `postgresql`, so a blank value saves as blank. Direct fix. → patch. |
| 2 | blind, edge | `notes` / `original_nl_prompt` have no size limit | low | Columns are `text`, so no 500; PRD sets no limit for these fields. Choosing one is a product decision; rejected for now, flagged to the user. |
| 3 | blind | `List.copyOf(q.getTags())` NPE on null tags or null elements | false | Column is `NOT NULL DEFAULT '{}'`, `setTags(null)` stores an empty list, and `@NotBlank` on elements blocks nulls. |
| 4 | blind | Author check sits in the controller and counts UTF-16 units | false | The controller is the only caller; counting UTF-16 units is at least as strict as Postgres's character count, so no overflow is possible. |
| 5 | blind | Relative `Location` URI | false | Relative references are valid (RFC 7231 §7.1.2), and the gateway keeps the `/api/sql/**` prefix. |
| 6 | blind | `id` ≤ 0 returns 404, not 400 | false | No such id can exist; 404 is the correct answer. |
| 7 | blind, edge | `is_llm_generated` and `original_nl_prompt` not checked against each other | false | The PRD defines no rule linking them. |
| 8 | blind | `stripIndex` hides which tag failed | false | The frozen matrix requires `field` = `tags` for tag errors. |
| 9 | blind | The controller test's `saved()` helper repeats the defaults logic | low | The defaults are covered for real in `SavedQueryServiceTest` and `SavedQueryApiIT`; no harm. Rejected. |
| 10 | blind | The IT doesn't cover 400, anonymous, or ignored fields end to end | low | `@WebMvcTest` covers each of these; the IT round-trip covers persistence. Rejected. |
| 11 | blind, edge | Tags are not trimmed or deduplicated | false | No requirement asks for it. |
| 12 | edge | The top-level `field` is unspecified when several fields fail at once | low | True; all errors are listed in `details`. Fixing it adds ordering logic for a rare case. Rejected. |

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- expected: BUILD SUCCESS, all tests green.
- `docker compose up -d --build sql-service`, then `curl -i -X POST localhost:8084/api/sql/queries -H 'Content-Type: application/json' -d '{"title":"t","description":"d","sql_content":"SELECT 1"}'` -- expected: 201 with `author: "anonymous"`; GET of its id returns 200.

**Post-review change (user request, 2026-10-18):** `notes` limited to 2000 characters and `original_nl_prompt` to 1000 (the Generate prompt limit in EXPERIENCE.md); over-limit returns 400 `VALIDATION_FAILED` naming the field. Resolves triage row 2.
