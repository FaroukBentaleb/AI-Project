---
title: 'Story 4.3: Standalone validate endpoint and fixture suite'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '7dacc58'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-1-structural-read-only-check.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-2-function-and-pattern-policy.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The validator (4.1, 4.2) is a pure library with no way to call it; the demo needs to paste any SQL and see the Validation Result with the Safety Note, and the FR-11 table must be proven through the service.

**Approach:** Expose `ReadOnlyValidator` as a Spring bean (`ValidatorConfig`) with `sql.validator.blocked-functions` bound to `FunctionPolicy`, and add `POST /api/sql/validate` (FR-12) returning addendum §B `{accepted, normalized_sql, rejections[{code, reason}], warnings[], safety_note}` with 200. Run the FR-11 fixture table and the parser-coverage set (SM-1, SM-C1) as parameterized MockMvc tests. README gains the Safety Note and read-only DB user guidance (NFR4).

## Boundaries & Constraints

**Always:** Parse only, never execute or save SQL; accepted and rejected are both 200; Safety Note on every result; 400 errors use the existing `VALIDATION_FAILED` envelope with `field`.

**Never:** No schema change or read-only flag on saved queries (4.4), no generation (epic 5), no frontend or other-service change, no caller-identity requirement on validate.

## I/O & Edge-Case Matrix

| Scenario | Input | Expected |
|----------|-------|----------|
| FR-11 rejected | data-modifying CTE, `SELECT INTO`, `; DROP`, fullwidth `; ＤＲＯＰ`, `pg_read_file`, `dblink`, `COPY`, `FOR UPDATE` | 200, `accepted=false`, codes per table (CTE/COPY may be `SQL_PARSE_ERROR`) |
| FR-11 accepted | fullwidth `ＳＥＬＥＣＴ 1`, week-over-week UNION and CTE | 200, accepted, normalized |
| pg_sleep | `SELECT pg_sleep(60)` | accepted + 1 Warning |
| Parser coverage | window functions, named WINDOW, `FILTER (WHERE …)`, `DISTINCT ON`, interval arithmetic, `::interval` | accepted, no rejection, no Warning |
| Parse error | `SELEC 1 FROM` | 200, `SQL_PARSE_ERROR` |
| Blank / missing | `{}`, `""`, whitespace, null, empty or malformed body | 400 `VALIDATION_FAILED` |
| Length | 20,000 chars / 20,001 chars | 200 / 400 `VALIDATION_FAILED` field `sql_content` |
| Method | `GET /api/sql/validate` | 405 `METHOD_NOT_ALLOWED` |
| Latency | realistic CTE + window + FILTER query | p95 < 300 ms (NFR2) |

## Decisions

- `sql.validator.blocked-functions` is bound as a raw `String` and split on commas in `ValidatorConfig`, not as `List<String>`: list conversion depends on a `ConversionService` that is absent in plain contexts, which made a configured list bind as one entry. Absent or blank keeps the PRD defaults (fail closed); a non-empty list replaces them.
- Request is `{sql_content}` with `@NotBlank @Size(max = 20000)` on the raw string (pre-normalization), matching the saved-query limit.
- Fixtures whose PRD outcome allows `SQL_PARSE_ERROR` (data-modifying CTE, `COPY`) assert "every code is in the allowed set" rather than one code, so a JSqlParser upgrade cannot silently flip them to accepted.
- Controller tests use `@WebMvcTest` with no DataSource, which also proves the endpoint never touches the database. The p95 check is a separate failsafe IT through the full running service.
- Validate needs no `X-User-Id`; it neither reads nor writes user data.
- README section added to the root README (sql-service has none); Safety Note text identical to `SafetyNote.TEXT`.

</frozen-after-approval>

## Code Map

- `.../config/ValidatorConfig.java` -- `FunctionPolicy` and `ReadOnlyValidator` beans; property binding.
- `.../controller/ValidateController.java` -- `POST /api/sql/validate`.
- `.../dto/ValidateRequest.java`, `ValidateResponse.java` -- request validation, §B response shape.
- Tests: `.../controller/ValidateControllerTest.java` (36 cases, parameterized), `.../config/ValidatorConfigTest.java`, `.../controller/ValidatePerformanceIT.java`.
- `README.md` -- Safety Note and read-only DB user guidance.

## Tasks & Acceptance

**Execution:**
- [x] Validator bean with `sql.validator.blocked-functions` binding.
- [x] `POST /api/sql/validate` with request validation and §B response.
- [x] Parameterized FR-11 fixture and parser-coverage suite through the endpoint.
- [x] Controller tests for 400s, 405, response shape; p95 IT.
- [x] README Safety Note and NFR4 guidance.
- [x] Full `verify` green.

**Acceptance Criteria:**
- Given each FR-11 fixture, when POSTed to `/validate`, then 200 and `accepted`/`rejections` match the table.
- Given every parser-coverage idiom, when validated, then accepted.
- Given a blank body or sql_content over 20,000 chars, when POSTed, then 400 `VALIDATION_FAILED`.
- Validate p95 < 300 ms.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 342 tests green (294 surefire, 48 failsafe).
