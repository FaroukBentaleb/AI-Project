---
title: 'Story 7.1: Generate screen'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'b080caa'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-summary.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-5-validate-screen-read-only-signals.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-4-rag-grounding-team-examples.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/DESIGN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `POST /api/sql/generate` exists (Epic 6) but `/sql/generate` is a placeholder. Separately, the post-save toast treats any `warnings[]` as "not yet indexed", although validator Warnings now also arrive in `warnings[]` (flagged in 5.3).

**Approach:** Replace the placeholder with the Generate screen (FR29 display part): command bar with 1-1000 counter, personal-data notice, Ctrl/⌘+Enter, Generating state; on success, result tabs SQL · Examples (n) · Warnings (n), Copy (no usage), and the server Safety Note outside the tabs. Prompt and result live in `SqlSessionState`. Fix the toast rule to key the indexing variant on the server's "Not yet indexed" Warning only.

## Boundaries & Constraints

**Always:** Changes inside `frontend/src/app/features/sql/`; existing conventions and scoped tokens; Safety Note verbatim; nothing shown as generated without a 200.

**Never:** No backend change. No *Save to library* from Generate (7.2). No 422/503 actions, rejected-SQL display, retry countdown or manual-save path (7.3). No other module or global style touched.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|----------|---------------|----------|
| Idle | empty prompt | command bar, notice, counter 0/1000, *Generate* disabled |
| Blank / over limit | spaces only / 1,001 chars | *Generate* disabled, counter over-limit at 1,001, nothing sent |
| Library handoff | `history.state.prompt = "orders"` | prompt prefilled, not submitted |
| Generating | submit or Ctrl/⌘+Enter | `POST /generate {prompt}`; "Generating…" disabled, prompt read-only, polite indicator "Finding similar team queries, then generating…", previous result dimmed |
| Success | 200 | tabs on SQL with SQL code block + Copy (no usage call), Examples (n) titles linking to `/sql/queries/:id`, Warnings (n); Safety Note outside tabs; focus to result heading |
| No examples | `examples_used: []`, no-similar Warning | Examples (0) shows "No team examples were used."; Warnings (1) label warning-colored, warning rows styled distinct from SQL |
| Tabs keyboard | ←/→/Home/End | move and select (automatic activation); new result resets to SQL |
| Session | navigate away and back | prompt and result restored |
| 400 | `VALIDATION_FAILED` | server message inline under the prompt |
| Other failure | 422/503/504/network | assertive banner with the mapped message (504 → `LLM_UNAVAILABLE` copy); no tabs; actions come in 7.3 |
| Save toast | warnings `["Not yet indexed: …"]` | warning "Saved · not yet indexed (team examples unavailable)" |
| Save toast | only validator Warnings, read-only | warning "Saved · added to team examples · 1 read-only check warning" |

## Decisions

- Generate error handling is a pure `generateError(err)` → `{kind, message, code, retryAfterSeconds, details}` view model kept in session state, so 7.3 adds banner variants and actions without touching the request flow. 7.1 renders only the message (danger for `LLM_NOT_CONFIGURED`/`SQL_REJECTED`, warning otherwise). A gateway 504 or a non-JSON 502/503/504 maps to the `LLM_UNAVAILABLE` copy.
- A new run keeps the previous result (dimmed) until replaced; on failure the old result is cleared so a stale result never sits next to an error, same as Validate.
- Copy on Generate records no usage (no saved query). *Save to library* is left out; 7.2 adds it next to Copy and a `'generate'` `SaveSource`.
- Library handoff reads `history.state.prompt` once on init; it overrides the session prompt only when non-empty and does not clear the session result.
- Toast fix: the indexing variant applies when any Warning starts with "Not yet indexed" (`QueryIndexer.WARNING_PREFIX`). Other Warnings (validator) keep rule 2/3 text and append " · N read-only check warning(s)" as a warning toast, since the toast has no actions and detail does not re-show transient Warnings. Indexing wins when both are present.
- Prompt textarea grows with content (rows recomputed from line breaks, capped at 8); counter always visible as `n/1000`.

</frozen-after-approval>

## Code Map

- `core/models.ts` -- `GenerateResponse`, `ExampleRef`.
- `core/sql-api.service.ts` -- `generate()`.
- `core/session-state.service.ts` -- Generate prompt, result, error.
- `generate/generate-error.ts` -- error view model.
- `generate/generate.component.{ts,css}` -- the screen.
- `sql.routes.ts` -- route.
- `save/save-form.ts` -- toast rule fix.

## Tasks & Acceptance

**Execution:**
- [x] Models, API `generate()`, session fields, error view model.
- [x] Generate screen: command bar, Generating, success tabs, Safety Note, Examples links, inline 400 and basic banner.
- [x] Library handoff via `history.state`.
- [x] Toast rule fix.
- [x] Unit tests for every state above and the toast variants.
- [x] `ng test` and `ng build` green.

**Acceptance Criteria:**
- Given I submit a prompt and generation succeeds, then I see the SQL, any Warnings, the Safety Note, and Examples used as links.
- Given a "no similar queries" Warning, when shown, then it is visually distinct from the SQL and readable.
- The personal-data notice is always visible near the prompt box, and the submit button is disabled while generating.

## Spec Change Log

## Verification

**Commands:**
- `npx ng test --watch=false --browsers=ChromeHeadless` (CHROME_BIN=Edge) -- result: 176/176 SUCCESS (Edge 155 headless)
- `npx ng build` -- result: clean, no warnings
