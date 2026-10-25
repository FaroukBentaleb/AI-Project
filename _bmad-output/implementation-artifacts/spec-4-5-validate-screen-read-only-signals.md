---
title: 'Story 4.5: Validate screen and not-read-only signals in the UI'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '7dacc58'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-summary.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-3-standalone-validate-endpoint.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-4-read-only-flag-saved-queries.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/DESIGN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The validator exists only as an API. The Validate tab is a placeholder, and the Library, detail and copy flows don't show when a saved query is not read-only, which was deferred from Epic 3.

**Approach:** Replace the `/sql/validate` placeholder with the Validate screen (FR32, EXPERIENCE.md states Idle, Validating, Rejected, Accepted with or without Warnings, 400, network failure). It calls `POST /api/sql/validate`, renders the result header, Rejection Code chips with reasons, Warnings (n), Normalized SQL and the server Safety Note verbatim. Accepted results get Copy (no usage) and *Save to library*, which opens the Save form prefilled with the normalized SQL. Input and result live in a module-scoped session-state service so they survive in-app navigation. The Library gains the *Not read-only* card badge, a Read-only filter (Any · Yes · No) and the copy notice under the card. Detail gains the danger banner with reason chips and the copy notice (FR27, FR28, FR31). The toast rule reads the typed `read_only`.

## Boundaries & Constraints

**Always:** Changes stay inside `frontend/src/app/features/sql/`. Midnight Terminal styling uses the existing scoped tokens. Copy never claims "safe". Nothing shows as accepted without a server response.

**Never:** No backend change. No Generate screen or Generate session state (Epic 7). No global style. No other module touched.

## I/O & Edge-Case Matrix

| Scenario | Input | Expected |
|----------|-------|----------|
| Idle | empty paste area | explainer, *Validate* disabled, Ctrl/⌘+Enter hint |
| Over limit | 20,001 chars | counter red, field invalid, button disabled, nothing sent |
| Validating | submit | button "Validating…" and disabled |
| Rejected | `WITH x AS (INSERT …) SELECT 1` | "✕ Rejected · 1 problem", `DATA_MODIFYING_CTE` chip + reason, Normalized SQL, Safety Note, no Copy/Save |
| Accepted + Warnings | UNION ALL week-over-week / `pg_sleep` | "✓ Accepted · single read-only SELECT", Warnings (n), Copy (no usage call), *Save to library* |
| 400 | server `VALIDATION_FAILED` | server message inline under the field, no result |
| Network failure | status 0 | generic danger banner, no result panel |
| Session state | navigate away and back | input and result restored; a full reload clears them |
| Save from Validate | *Save to library* | `/sql/queries/new?from=validate`, SQL = normalized SQL, is_llm_generated false |
| Card | `read_only=false` | *Not read-only* badge; Copy shows "Copied. This query is not read-only — it modifies data." under the card |
| Filter | Read-only Yes / No | URL `ro=yes|no` → API `read_only=true|false`; counts as a filter |
| Detail | `read_only=false`, reasons | danger banner "Not read-only. Validation reasons:" + chip/reason per entry; Copy shows the notice |
| Toast | `read_only=false` | "Saved" (no team examples) |

## Decisions

- Reasons arrive as `"CODE: reason"` strings (4.4). The UI splits on the first `": "` when the prefix is an upper-snake code. Otherwise the whole string is shown as the reason with no chip.
- Session state is a `SqlSessionState` service provided on the SQL shell route, next to `ToastService`. It holds the Validate input, the result and a one-shot Save prefill. Being route-scoped, it survives tab switches and is cleared on a full reload. Epic 7 adds the Generate fields to it.
- The Save form consumes the prefill only when `from=validate` is in the URL and a prefill is pending. A stale or missing prefill gives a blank form.
- A new run clears the previous result and error when it is sent, so a failed run never leaves an old "Accepted" on screen.
- The Validate copy notice and card notice use the same `NOT_READ_ONLY_COPY_NOTICE` constant. The card notice stays until the next copy of another card or a re-render; it isn't timed, because it is a warning, not a confirmation.
- The card shows only *Not read-only*, not *Read-only ✓* (EXPERIENCE.md: "Detail also shows Read-only ✓"). A `null` read_only (not yet backfilled) shows no badge.
- Validate SQL limit and counter reuse `SAVE_LIMITS.sql_content` and `SQL_COUNTER_FROM` (20,000 / 19,000). Tab inserts two spaces; Esc then Tab leaves the field, the same as on Save.

</frozen-after-approval>

## Code Map

- `core/models.ts` -- `read_only`, `read_only_reasons`, `ValidateResponse`, `Rejection`.
- `core/sql-api.service.ts` -- `validate()`.
- `core/session-state.service.ts` -- Validate session state + Save prefill.
- `core/read-only.ts` -- `parseReason`, `NOT_READ_ONLY_COPY_NOTICE`.
- `validate/validate.component.{ts,css}` -- the screen.
- `library/query-card.component.ts`, `library/library-state.ts`, `library/library.component.ts` -- badge, notice, filter.
- `detail/query-detail.component.ts` -- banner, copy notice.
- `save/save-query.component.ts`, `save/save-form.ts` -- Validate prefill, typed toast rule.
- `sql.routes.ts`, `shell/sql-theme.css` -- route and shared code-chip, warning banner and safety-note primitives.

## Tasks & Acceptance

**Execution:**
- [x] Models, API `validate()`, reason parser.
- [x] Session state service and Save prefill from Validate.
- [x] Validate screen with every EXPERIENCE.md state.
- [x] Card badge, copy notice, Read-only filter.
- [x] Detail danger banner and copy notice; typed toast rule.
- [x] Unit tests for every Validate state and read-only signal.
- [x] `ng test` and `ng build` green.

**Acceptance Criteria:**
- Given I paste `WITH x AS (INSERT …) SELECT 1` and submit, then I see "Rejected", the `DATA_MODIFYING_CTE` reason and the Safety Note.
- Given a UNION ALL week-over-week query, when validated, then "Accepted" and any Warnings.
- Given a saved non-read-only query, when listed, opened or copied, then the badge shows, the detail lists the reasons, and the copy shows the not-read-only notice.

## Spec Change Log

## Verification

**Commands:**
- `npx ng test --watch=false --browsers=ChromeHeadless` (CHROME_BIN=Edge) -- result: 155/155 SUCCESS (Edge 155 headless)
- `npx ng build` -- result: clean, no warnings
