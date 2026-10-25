# Epic 4 summary: Read-Only Validator

The validator runs on JSqlParser 5.3 syntax trees in `com.devassisthub.sql.validation`, which is pure Java with no web and no DB. It is exposed as `POST /api/sql/validate`, stored on every Saved Query, and visible in the SQL module UI. The service never executes SQL.

Final verification: backend `mvn verify` gave 360 tests green (Story 4.4 run). Frontend `ng test` gave 155/155 (Edge headless), and `ng build` was clean.

## Delivered

- **4.1 Structural check:** plain SELECT, set operations and WITH are accepted only when every branch and CTE body is a SELECT. Writes, DDL, `SELECT INTO`, data-modifying CTEs, locking clauses, COPY/CALL/DO/EXPLAIN ANALYZE and multiple statements are rejected anywhere in the tree. Pre-processing strips fences and prose and applies NFKC.
- **4.2 Function and pattern policy:** `BLOCKED_FUNCTION` (configurable via `sql.validator.blocked-functions`, fail-closed defaults) and non-blocking Warnings (e.g. `pg_sleep`).
- **4.3 Validate endpoint:** `{accepted, normalized_sql, rejections[{code, reason}], warnings[], safety_note}`. Accepted and rejected both return 200. Blank or over-20,000-character input returns 400 `VALIDATION_FAILED`. Includes the FR-11 fixture suite, parser-coverage idioms, a p95 < 300 ms IT, and the README Safety Note.
- **4.4 Read-only flag:** `V6__read_only.sql` adds `read_only` and `read_only_reasons` (`"CODE: reason"`). Create, PUT and PATCH-with-SQL validate and store the normalized text. Mutation responses carry the Warnings. Adds the `read_only=true|false` list filter and an idempotent startup backfill.
- **4.5 UI:** the Validate screen (explainer, paste area with 20,000 limit and counter, Ctrl/⌘+Enter, the "✕ Rejected · n problem(s)" / "✓ Accepted · single read-only SELECT" header with focus, Rejection chips, Warnings (n), Normalized SQL, and the server Safety Note verbatim). Accepted results get Copy (no usage) and *Save to library*, which prefills the Save form through the new `SqlSessionState`. Input and result survive in-app navigation. Also clears the Epic 3 deferrals: the *Not read-only* card and detail badges, the Library Read-only filter (Any · Yes · No, `ro=` in the URL), the copy notice "Copied. This query is not read-only — it modifies data." on card and detail, the detail danger banner with reason chips, and the read-only aside row. The "Saved" toast variant now reads the typed `read_only`.

## Deferred

- **Epic 5/6 (indexing):** the real team-example status. The UI keeps honouring `indexed` when present.
- **Epic 7 (Generate):** the Generate screen and its session-state fields (`SqlSessionState` is ready to extend), and the Save prefill from Generate and from outages.
- The card's copy notice is untimed: it stays until the card re-renders. A detail page shows reasons as stored and doesn't re-validate on view.
- Not done in v1: syntax highlighting.
