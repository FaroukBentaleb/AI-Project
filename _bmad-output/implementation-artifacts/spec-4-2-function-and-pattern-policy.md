---
title: 'Story 4.2: Function and pattern policy'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: 'cb07b38'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-1-structural-read-only-check.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A structurally pure SELECT can still read server files, open remote connections, or change session state through built-in functions, and some read-only shapes are performance traps.

**Approach:** Extend the 4.1 `validation/` walker (FR-10, addendum §C). Every `Function` node is reduced to its bare name (lower-cased, schema prefix and quotes stripped) and checked against a configurable blocklist (`FunctionPolicy`, defaults = PRD list) → `BLOCKED_FUNCTION` naming the function. Non-blocking Warnings: `pg_sleep`, explicit `CROSS JOIN`, comma join without a linking column equality, top-level `SELECT *` without LIMIT or aggregation.

## Boundaries & Constraints

**Always:** Stay pure (no Spring, no DB, never execute SQL); fail closed; an ordinary aggregate report has no Warning (SM-C3).

**Never:** No endpoint or Spring property binding (4.3), no schema change (4.4), no detection of user-defined side-effect functions (documented gap).

## I/O & Edge-Case Matrix

| Scenario | Input | Expected |
|----------|-------|----------|
| FR-11 fixtures | `SELECT pg_read_file('/etc/passwd')`, `SELECT dblink('…','DROP TABLE orders')` | `BLOCKED_FUNCTION`, reason names function |
| Every family | file, lo_*, dblink_*, backend control, set_config/nextval/setval/pg_advisory_lock*, query_to_xml* | `BLOCKED_FUNCTION` |
| Disguises | `pg_catalog.pg_read_file`, `PG_READ_FILE`, `"pg_read_file"`, `pg_catalog."pg_read_file"`, fullwidth | `BLOCKED_FUNCTION` |
| Deep in tree | FROM table function, sub-select, CTE, UNION branch, JOIN ON, CASE | `BLOCKED_FUNCTION` |
| Lookalikes | string literal `'pg_read_file(1)'`, column `pg_read_file_size` | accepted |
| pg_sleep | `SELECT pg_sleep(60)` (also qualified, `pg_sleep_for`) | accepted + Warning |
| Joins | `FROM orders o, customers c WHERE o.customer_id = c.id` / explicit JOIN ON | no Warning |
| Joins | `FROM orders, customers`, filter-only WHERE, self-equality, `CROSS JOIN`, in sub-select | Warning |
| SELECT * | top-level, no LIMIT/FETCH/GROUP BY/aggregate (incl. `WITH … SELECT *`) | Warning |
| No warning | aggregate report with GROUP BY, `SELECT * … LIMIT`, `o.*`, `*` only in sub-select | no Warning |

## Decisions

- Blocklist lives in a pure `FunctionPolicy` (constructor list + `DEFAULT_BLOCKED_FUNCTIONS`); `ReadOnlyValidator()` uses defaults, `ReadOnlyValidator(FunctionPolicy)` takes a custom list. Binding `sql.validator.blocked-functions` to it is left to 4.3 (where the validator becomes a bean), per the 4.1 decision.
- Family entries use a trailing `*` as a prefix wildcard: `dblink_*`, `pg_advisory_lock*`, `query_to_xml*` ("and its variants" = `query_to_xml_and_xmlschema` etc.). Entries are trimmed and lower-cased.
- Name matching is on the last name segment only, quotes stripped, case-insensitive. A quoted mixed-case name (a different identifier in Postgres) is still blocked — fail closed.
- Rejection reason format follows addendum: `"<name> is not allowed"`; each function reported once.
- Warnings are returned only when the statement is accepted; deduplicated, discovery order. Wording is specific and actionable (SM-C3).
- `pg_sleep*` (incl. `pg_sleep_for`, `pg_sleep_until`) warns, never rejects.
- Comma join "linking" = any `Column = Column` predicate in that select's WHERE whose table qualifiers differ (unqualified columns count as linking — lenient, avoids noise). Checked on every PlainSelect, not only top level. A comma join with `LATERAL`/ON is not a plain comma join.
- SELECT * Warning: only a top-level PlainSelect with a bare `*` (not `t.*`), and no LIMIT, FETCH, TOP, GROUP BY, HAVING, or a known aggregate function among select items. Set operations are not flagged.
- 4.1 test fixture `SELECT * FROM (sub) o JOIN (sub) c …` changed to `SELECT o.id …` because it now (correctly) carries the SELECT * Warning; the 4.1 behaviour itself is unchanged.

</frozen-after-approval>

## Code Map

- `.../validation/FunctionPolicy.java` -- blocklist defaults, prefix wildcards, bare-name extraction.
- `.../validation/ReadOnlyValidator.java` -- function-checking `ExpressionDeParser`, join and SELECT * warnings.
- `.../validation/RejectionCode.java` -- adds `BLOCKED_FUNCTION`.
- Tests: `.../validation/FunctionPolicyTest.java` (parameterized, 63 cases); `ReadOnlyValidatorTest.java` one fixture adjusted.

## Tasks & Acceptance

**Execution:**
- [x] `BLOCKED_FUNCTION` code and `FunctionPolicy` with PRD defaults.
- [x] Function check over the full tree with schema/quote/case stripping.
- [x] Warnings: pg_sleep, CROSS JOIN, unlinked comma join, top-level SELECT *.
- [x] Parameterized tests over FR-10/FR-11 fixtures, disguises, lookalikes, warning and no-warning cases.
- [x] Full `verify` green.

**Acceptance Criteria:**
- Given `SELECT pg_read_file('/etc/passwd')` or `SELECT dblink('…','DROP TABLE orders')`, when validated, then rejected with `BLOCKED_FUNCTION` naming the function.
- Given `SELECT pg_sleep(60)`, when validated, then accepted with a performance Warning.
- Given `FROM orders o, customers c WHERE o.customer_id = c.id`, when validated, then no cross-join Warning; `FROM orders, customers` gets one.
- Given `SELECT region, count(*) … GROUP BY region` with no LIMIT, when validated, then no Warning.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 302 tests green (255 surefire incl. 63 new, 47 failsafe).
