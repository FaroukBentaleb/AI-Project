# Epic 4 Context: Read-Only Validator

<!-- Compiled from planning artifacts (epics-sql-service.md Epic 4, PRD FR-9..FR-12/FR-32, NFR4, addendum §C). Edit freely. -->

## Goal

Anyone can check whether SQL is structurally read-only, through the API and the Validate screen. Every Saved Query carries a read-only flag and reasons. Validation runs on syntax trees from a pinned JSqlParser 5.x, and the full PRD fixture suite proves it.

## Stories

- 4.1: Structural read-only check (FR9)
- 4.2: Function and pattern policy (FR10)
- 4.3: Standalone validate endpoint and fixture suite (FR11, FR12, NFR4)
- 4.4: Read-only flag on Saved Queries (`V6__read_only.sql`; FR1/FR4/FR5 parts)
- 4.5: Validate screen and not-read-only signals in the UI (FR27, FR28, FR31, FR32)

## Requirements & Constraints

- Pre-processing: strip markdown fences and surrounding prose, NFKC-normalize; the normalized text is what is validated, returned, and stored.
- Rejection Codes: `SQL_PARSE_ERROR`, `MULTIPLE_STATEMENTS`, `NON_SELECT_STATEMENT`, `SELECT_INTO`, `DATA_MODIFYING_CTE`, `LOCKING_CLAUSE`, `BLOCKED_FUNCTION` (4.2).
- Accepted: plain SELECT; set operations whose branches are all SELECTs; WITH whose CTE bodies are all SELECTs. Rejected anywhere in the tree: writes, DDL, SELECT INTO, data-modifying CTE, FOR UPDATE/SHARE, COPY/CALL/DO/EXPLAIN ANALYZE.
- Week-over-week (UNION ALL and CTE forms) and parser-coverage idioms (window functions, `FILTER (WHERE …)`, `DISTINCT ON`, interval arithmetic) must be accepted.
- Validate endpoint `POST /api/sql/validate` → 200 `{accepted, normalized_sql, rejections[{code, reason}], warnings[], safety_note}`; blank or > 20,000 chars → 400 `VALIDATION_FAILED`; p95 < 300 ms.
- The service never executes SQL. Safety Note: the real safeguard is running SQL as a read-only DB user.

## Technical Decisions

- `com.devassisthub.sql.validation` package: pure Java, no Spring web, no DB.
- JSqlParser `5.3` pinned in `sql-service/pom.xml`.
- Tree walk reuses JSqlParser's `SelectDeParser`/`ExpressionDeParser` traversal (overrides on PlainSelect, SetOperationList, ParenthesedSelect, WithItem) so every nested select is visited.
- Schema changes only via new Flyway migrations (V6+); `ddl-auto: validate`; Testcontainers 1.21.4; shared `AbstractIntegrationTest`.
