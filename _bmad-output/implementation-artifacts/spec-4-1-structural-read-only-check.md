---
title: 'Story 4.1: Structural read-only check'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: 'cb07b38'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A "starts with SELECT" check misses writes hidden in CTEs, INTO clauses, locking clauses, or stacked and fullwidth-disguised statements.

**Approach:** A pure `validation/` component (FR9) with JSqlParser 5.x pinned. Pre-processing NFKC-normalizes and strips markdown fences; the normalized text is parsed and returned. It requires a single statement, requires that statement to be a `Select`, then walks the whole tree (set operations, every CTE body, sub-selects in FROM/WHERE/SELECT/JOIN) and returns a `ValidationResult` with Rejection Codes `SQL_PARSE_ERROR`, `MULTIPLE_STATEMENTS`, `NON_SELECT_STATEMENT`, `SELECT_INTO`, `DATA_MODIFYING_CTE`, `LOCKING_CLAUSE`.

## Boundaries & Constraints

**Always:** Normalized text is exactly what was parsed and is returned in every result; fail closed (any walker exception → rejected).

**Never:** No SQL execution, no Spring web, no DB, no endpoint (4.3), no function blocklist or Warnings (4.2), no schema change (4.4).

## I/O & Edge-Case Matrix

| Scenario | Input | Expected |
|----------|-------|----------|
| Plain / set ops / CTE | `SELECT 1`, UNION/INTERSECT/EXCEPT, WITH (incl. RECURSIVE), week-over-week both forms | accepted, no rejections |
| Parser idioms | window fn, `FILTER (WHERE …)`, `DISTINCT ON`, interval arithmetic | accepted |
| Keywords in literals | `SELECT 'drop table orders; …'` | accepted |
| Fullwidth | `ＳＥＬＥＣＴ 1` | accepted, `normalized_sql` = `SELECT 1` |
| Fences / prose | ```` ```sql … ``` ```` with prose around | fence body validated and returned |
| Data-modifying CTE | `WITH x AS (INSERT/UPDATE/DELETE …) SELECT …` | `DATA_MODIFYING_CTE` |
| SELECT INTO | top-level or inside a UNION branch | `SELECT_INTO` |
| Stacked | `SELECT 1; DROP TABLE orders`, fullwidth `；`/`ＤＲＯＰ` | `MULTIPLE_STATEMENTS` |
| Locking | FOR UPDATE / FOR SHARE, top-level, in sub-select, in CTE | `LOCKING_CLAUSE` |
| Non-select | UPDATE, DELETE, INSERT, DROP, CREATE, MERGE, CALL, EXPLAIN ANALYZE, `WITH … DELETE` | `NON_SELECT_STATEMENT` |
| Unparseable | COPY, DO, garbage, LLM refusal prose | `SQL_PARSE_ERROR` (PRD allows for COPY) |
| Empty | `null`, blank | `SQL_PARSE_ERROR`, `normalized_sql` = `""` |

## Decisions

- JSqlParser pinned at **5.3** (latest stable 5.x with Postgres `ParenthesedInsert/Update/Delete` CTE bodies, so data-modifying CTEs yield `DATA_MODIFYING_CTE`, not `SQL_PARSE_ERROR`).
- Tree walk subclasses JSqlParser's `SelectDeParser` (wired to an `ExpressionDeParser`) instead of a hand-written visitor: the deparser already visits every nested select in FROM, JOINs, WHERE, select items, CTEs and set operations. Overrides check `getForMode()` on PlainSelect/SetOperationList/ParenthesedSelect, `getIntoTables()`/`getIntoTempTable()` on PlainSelect, and reject any `WithItem` whose body isn't a `ParenthesedSelect` (not recursed into).
- NFKC is applied **before** fence stripping, so fullwidth backticks or semicolons cannot hide anything.
- Fence handling: if a fence exists, only the first fence body is kept (prose dropped); otherwise the whole text is used. Result is `strip()`ped.
- Early structural failures (parse, multiple, non-select) return a single rejection; tree-walk rejections are collected, deduplicated, in discovery order.
- A trailing `;` is one statement (accepted).
- `ReadOnlyValidator` is a plain stateless class (no `@Component`); 4.3 wires it as a bean together with the 4.2 config.
- `ValidationResult` already carries an (empty) `warnings` list so 4.2 adds content, not shape.
- COPY and DO are not parseable by JSqlParser 5.3 → `SQL_PARSE_ERROR` (allowed by FR-11). `CALL` parses as Execute → `NON_SELECT_STATEMENT`.

</frozen-after-approval>

## Code Map

- `sql-service/pom.xml` -- `com.github.jsqlparser:jsqlparser:5.3`.
- `.../validation/SqlNormalizer.java` -- NFKC + fence stripping.
- `.../validation/ReadOnlyValidator.java` -- parse, single-statement, Select check, deparser-based tree walk.
- `.../validation/ValidationResult.java`, `Rejection.java`, `RejectionCode.java` -- result types.
- Tests: `.../validation/ReadOnlyValidatorTest.java` (parameterized, 50 cases).

## Tasks & Acceptance

**Execution:**
- [x] Pin JSqlParser 5.3.
- [x] Normalizer (NFKC, fences, prose).
- [x] Validator: parse errors, multiple statements, non-select, tree walk for INTO / locking / data-modifying CTE.
- [x] Parameterized unit tests over the FR-11 structural fixtures, week-over-week, parser idioms, nested cases.
- [x] Full `verify` green.

**Acceptance Criteria:**
- Given a plain SELECT, a UNION ALL of SELECTs, or a WITH whose bodies are all SELECTs, when validated, then it is accepted and the normalized SQL is returned.
- Given `WITH x AS (INSERT …) SELECT 1`, `SELECT * INTO t FROM orders`, `SELECT 1; DROP TABLE orders`, `SELECT 1; ＤＲＯＰ ＴＡＢＬＥ orders`, `SELECT … FOR UPDATE`, an UPDATE, or `COPY …`, when validated, then each is rejected with the FR-11 code (or `SQL_PARSE_ERROR` where allowed).
- Given fullwidth `ＳＥＬＥＣＴ 1`, when validated, then it is accepted and `SELECT 1` is returned.
- All cases are parameterized unit tests.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 239 tests green (192 surefire incl. 50 new, 47 failsafe).
