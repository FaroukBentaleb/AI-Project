---
title: 'Story 1.3: Browse the Team Library'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '73201b2'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Saved Queries can be created and opened by id, but nobody can see what the team already has.

**Approach:** Add `GET /api/sql/queries` (FR-3) returning every Author's queries, paged and sorted, with paging metadata. Raw `page`/`size`/`sort` params are parsed by a small helper (not Spring's `Pageable` resolver) so the sort whitelist and clamp are explicit and unknown fields can never reach Spring Data as a `PropertyReferenceException` (500).

## Boundaries & Constraints

**Always:** Story 1.1 error envelope; `field` is the snake_case param name. Items use the Story 1.2 `SavedQueryResponse` (`warnings: []`). Sort names are the snake_case API names. Never commit outside the story.

**Never:** No search/filter params (Story 1.4), no `use_count`/`last_used_at` sort (Story 1.8), no deleted/deprecated logic (Stories 1.6/1.7). No new migration (indexes arrive in V2, Story 1.4). No change to POST/GET-by-id behaviour.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Default list | rows by several authors; no params | 200; `page` 0, `size` 20, sorted created_at desc (id desc tie-break); all authors; `total_elements`, `total_pages` | — |
| Clamp | `size=500` | 200, `size` 100 | — |
| Paging | `page=1&size=2` with 3 rows | 1 item, `total_pages` 2; page past end → empty `content` | — |
| Whitelisted sort | `sort=title[,asc\|desc]`, `created_at`, `updated_at` | sorted accordingly; direction defaults to asc when given a field | — |
| Unknown sort | any other field (incl. `use_count`, `author`, camelCase `createdAt`), bad direction, >2 parts | 400 `VALIDATION_FAILED`, `field: "sort"` | never 500 |
| Bad page/size | `page<0`, `size<1`, non-numeric | 400 `VALIDATION_FAILED`, `field` = param | — |

## Decisions

- Sort syntax is Spring-style `sort=<field>[,asc|desc]`, one field only; direction defaults to `asc` when a field is given (Spring convention). Repeated `sort` params bind as a joined string and are rejected as malformed.
- `id` in the same direction is always added as a tie-breaker for stable page boundaries.
- Response shape: `{content, page, size, total_elements, total_pages}`; `size` is the effective (clamped) size.
- `size<1` and `page<0` are 400 rather than silently corrected (only the >100 clamp is mandated).
- Title sort uses the database collation (case-insensitive-ish under `en_US.utf8`), no `lower()`.

</frozen-after-approval>

## Code Map

- `sql-service/.../service/LibraryPaging.java` -- new; `of(page,size,sort)` → `Pageable`; whitelist map snake_case → entity property; throws `ApiException` 400.
- `sql-service/.../service/SavedQueryService.java` -- add `list(Pageable)` → `repository.findAll`.
- `sql-service/.../dto/PageResponse.java` -- new generic record with `from(Page, mapper)`.
- `sql-service/.../controller/SavedQueryController.java` -- add `@GetMapping` list with three optional `@RequestParam`s.
- Tests: `LibraryPagingTest` (unit), `SavedQueryControllerTest` (`@WebMvcTest`), `SavedQueryListIT` (Testcontainers `postgres:16`).

## Tasks & Acceptance

**Execution:**
- [x] `service/LibraryPaging.java` -- defaults, clamp, whitelist, direction parsing, page/size bounds.
- [x] `dto/PageResponse.java` -- paging envelope.
- [x] `service/SavedQueryService.java` -- `list(Pageable)`.
- [x] `controller/SavedQueryController.java` -- `GET /api/sql/queries`.
- [x] `LibraryPagingTest` -- every matrix row at unit level.
- [x] `SavedQueryControllerTest` -- default metadata, clamp, 400s (sort, page, size, non-numeric) without calling the service.
- [x] `SavedQueryListIT` -- multi-author default order + metadata, clamp, paging, title asc/desc and updated_at on real Postgres, unknown sort 400.

**Acceptance Criteria:**
- Given saved queries by several authors, when I GET `/api/sql/queries`, then I see all of them newest first with `total_elements`, `total_pages`, `page`; `size=500` yields `size` 100; `sort=use_count` yields 400 `VALIDATION_FAILED`.

## Implementation Notes

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 82 tests green (71 surefire, 11 failsafe).
