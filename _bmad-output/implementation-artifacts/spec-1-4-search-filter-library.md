---
title: 'Story 1.4: Search and filter the library'
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

**Problem:** The library can be browsed page by page, but nobody can find a query by words, tag, author, or provenance.

**Approach:** Add `q`, `tags`, `author`, `llm_generated` to `GET /api/sql/queries` (FR-4), AND-combined, keeping the Story 1.3 paging/sort helper unchanged. Raw params are parsed by a `LibraryFilter` helper (same pattern as `LibraryPaging`), executed by a small native-SQL repository fragment. Add `V2__search_indexes.sql` (author, created_at, GIN on tags).

## Boundaries & Constraints

**Always:** Story 1.1 error envelope, `field` = snake_case param. Story 1.3 response shape, defaults, clamp, sort whitelist and tie-breaker unchanged. Only whitelisted columns and bound parameters reach SQL.

**Never:** No `deprecated` filter (1.7), `read_only` (Epic 4), deleted logic (1.6). No edit of V1. No change to POST/GET-by-id.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Text search | "Weekly orders by region", "Top customers"; `q=ORDERS` | only titles/descriptions containing "orders", case-insensitive | — |
| Literal wildcards | `q=100%`, `q=r_c` | `%`/`_` match literally | — |
| Tags any-of | tagged `reporting`, `ops`; `tags=reporting,finance` | rows having any listed tag | — |
| Author / provenance | `author=alice`, `llm_generated=true\|false` | exact (case-sensitive) author; boolean match | — |
| Combined | all four + `page`/`size`/`sort` | AND of all; `total_elements`/`total_pages` reflect filtered set | — |
| Blank values | `q=  `, `tags=,` | treated as no filter | — |
| Invalid | `llm_generated=maybe`/`1`; `q`>200; `author`>100; >10 tags; tag>50 | 400 `VALIDATION_FAILED`, `field` = param | never 500 |
| Migration | fresh DB | V1 then V2 applied; three indexes exist, tags index is GIN | — |

## Decisions

- `tags` is one comma-separated param; entries trimmed, blanks dropped, duplicates removed. Limits mirror the create limits (≤10 tags, each ≤50); `q` ≤200 (title limit); `author` ≤100 (column limit).
- `llm_generated` is parsed strictly (`true`/`false`, case-insensitive), not with Spring's lenient Boolean conversion (which accepts `1`/`yes`/`on`).
- `author` match is exact and case-sensitive (identity is an opaque header value).
- `q` is a substring `LIKE` on `lower(title)`/`lower(description)` with `%`, `_`, `\` escaped; no full-text/trigram index (not required; seed-sized data).
- Unknown parameter *names* are ignored (unchanged from 1.3); only invalid *values* of known params are 400.
- Search runs as native SQL (`tags && cast(:tags AS text[])`) because Hibernate 6.5 criteria binds list params as `varchar[]`, for which PostgreSQL has no `&&` against `text[]`. Sort is mapped from the `LibraryPaging` whitelist to columns; the unfiltered list also goes through this path.

</frozen-after-approval>

## Code Map

- `sql-service/src/main/resources/db/migration/V2__search_indexes.sql` -- new; btree author, created_at; GIN tags.
- `sql-service/.../service/LibraryFilter.java` -- new record; `of(q,tags,author,llm)` parse/validate → 400; `escapeLike`.
- `sql-service/.../repository/SavedQuerySearch.java` + `SavedQuerySearchImpl.java` -- new fragment; native filtered select + count, whitelisted ORDER BY.
- `sql-service/.../repository/SavedQueryRepository.java` -- extends `SavedQuerySearch`.
- `sql-service/.../service/SavedQueryService.java` -- `list(LibraryFilter, Pageable)`.
- `sql-service/.../controller/SavedQueryController.java` -- four new optional params.
- Tests: `LibraryFilterTest`, `SavedQueryControllerTest`, `SavedQuerySearchIT` (Testcontainers `postgres:16`).

## Tasks & Acceptance

**Execution:**
- [x] `V2__search_indexes.sql`.
- [x] `LibraryFilter` -- parsing, blanks, limits, strict boolean, LIKE escaping.
- [x] `SavedQuerySearch` fragment + repository wiring.
- [x] Service and controller signatures.
- [x] `LibraryFilterTest` -- blank/none, trim/dedupe, boundaries, each 400, escaping.
- [x] `SavedQueryControllerTest` -- filters passed through, blanks → `NONE`, invalid values 400 without calling the service; existing list tests updated to the new signature.
- [x] `SavedQuerySearchIT` -- q case-insensitive, literal wildcards, tags any-of, author, llm_generated, combined AND with paging, invalid 400, V2 indexes present.

**Acceptance Criteria:**
- Given "Weekly orders by region" and "Top customers", when I search `q=ORDERS`, then only the first is returned; `tags=reporting,finance` returns queries with any of those tags; `q`+`tags`+`author`+`llm_generated` apply together with paging intact; V2 applies cleanly on top of V1.

## Implementation Notes

- First attempt used `JpaSpecificationExecutor` + `HibernateCriteriaBuilder.collectionOverlaps`; it failed on real Postgres (`text[] && character varying[]`), so the native fragment replaced it. Story 1.3's list tests (`SavedQueryListIT`) now also exercise the native path and still pass.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 104 tests green (86 surefire, 18 failsafe).
