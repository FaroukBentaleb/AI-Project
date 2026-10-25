---
title: 'Story 3.4: Save a query manually'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '504d215'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-1-sql-module-shell-identity-errors.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-2-save-and-view-saved-query.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Developers have no way to add a hand-written query to the Team Library from the browser.

**Approach:** Replace the `/sql/queries/new` placeholder with the Save form (EXPERIENCE.md): Title, Description and SQL required with live counters; Tags, Notes and Dialect under a collapsed *More options ▸*. Client validation mirrors `CreateSavedQueryRequest`; server 400s map onto `field`. On 201 the user lands on `/sql/queries/{id}` with the post-mutation toast.

## Boundaries & Constraints

**Always:** Work in `frontend/src/app/features/sql/`; 3.1–3.3 conventions (standalone components, signals, `SqlApiService`, `toUiError`, Midnight Terminal tokens, Karma/Jasmine with `HttpTestingController`). Spines win over the epic.

**Never:** No backend changes; no Generate/Validate/outage prefill wiring (Epic 7); no detail screen (3.5); no other-module or global-style changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Save | title, description, SQL filled; submit or Ctrl/⌘+Enter | `POST /queries` snake_case body (tags `[]`, notes null, dialect `postgresql`, is_llm_generated false); button "Saving…" disabled; on 201 → `/sql/queries/{id}` + toast | — |
| Missing / blank required | submit | inline "X is required.", `aria-invalid`, focus first invalid; no request; then live per field | — |
| Over limit | title > 200, description > 2000, SQL > 20,000, notes > 2000 | inline "at most N characters", counter over-limit; SQL counter only past 19,000; Save disabled above 20,000 | — |
| Tags | 11th chip | chip refused, inline "Up to 10 tags."; each tag ≤ 50 (input maxlength) | — |
| Invalid optional field | error on tags/notes | *More options* expands, focus moves there | — |
| Server 400 with form `field` | `VALIDATION_FAILED`, field e.g. `tags` | server message inline on that field, focused | — |
| Server 400 without form field / other failure | `field` null or `x_user_id`, 5xx, network | form-level danger banner (`toUiError` copy) | form stays editable |
| Toast | 201 response | `warnings[]` non-empty → warning "Saved · not yet indexed (team examples unavailable)"; else `read_only=false` or deprecated → "Saved"; else "Saved · added to team examples" | — |
| Cancel / leave, pristine | Cancel, tab, link | leaves at once (back to previous surface; Library if the form was the entry page) | — |
| Cancel / leave, dirty | any navigation | "Discard your changes?" — *Keep editing* (initial focus, Esc) stays; *Discard* leaves; reload/close uses `beforeunload`; never stacks on another dialog | — |

## Decisions

- **No `@angular/forms`.** The form has six fields and its rules are a pure function (`save/save-form.ts`) shared with Edit in 3.5; signals + `(input)` bindings cover it, as 3.1's dialog did. Adding the package would mean a dependency/lockfile change for little gain. Revisit if Edit's merge-on-reload makes per-field dirty tracking heavy.
- Field keys are the API's snake_case names, so the server `field` maps 1:1; only form fields are shown inline, anything else goes to the banner.
- Required = non-blank after trim (matches `@NotBlank`); lengths counted on the raw value; title/description trimmed in the body.
- Field order: Title, Description, SQL (spine listing order); "first invalid" follows it.
- Dialect is a single-option select (PostgreSQL), per EXPERIENCE.md.
- Toast infrastructure added here: route-scoped `ToastService` (one at a time, ~5 s, paused on hover/focus) and `<sql-toast>` host in the shell, polite live region. `read_only` is honoured when present (API adds it in Epic 4).
- Discard-changes modal is a reusable `shared/discard-modal.component.ts`; leaving is guarded by a `CanDeactivate` (`confirmLeaveSaveForm`), so Cancel, tabs and links share one path.
- Tags input gained a `rejected` output (limit message); existing behaviour unchanged except the refused text stays in the input.
- Tab in the SQL textarea inserts two spaces; Esc then Tab leaves (spine `[ASSUMPTION]`).
- Prefill by entry point is deferred to Epic 7: `SaveQueryComponent.prefill(draft, expandMore)` is the hook; the module session-state service does not exist yet.

</frozen-after-approval>

## Code Map

- `features/sql/save/save-form.ts` -- limits, draft, validation, create body, toast rule.
- `features/sql/save/save-query.component.ts|css` -- form, submit flow, 400 mapping, focus, Cancel, guard, beforeunload.
- `features/sql/shared/toast.service.ts`, `toast.component.ts` -- toast; host in `shell/sql-shell.component.ts`.
- `features/sql/shared/discard-modal.component.ts` -- discard dialog.
- `features/sql/shared/tags-input.component.ts` -- `rejected` output.
- `features/sql/core/sql-api.service.ts` -- `createQuery`.
- `features/sql/sql.routes.ts` -- route + guard, `ToastService` provider.

## Tasks & Acceptance

**Execution:**
- [x] `createQuery` + test.
- [x] Form model (limits = API, required, tags, body, toast rule) + tests.
- [x] Save form component (collapsed More, submit, Saving…, Ctrl+Enter, counters, SQL-limit disable, focus first invalid incl. More fields, 400 field mapping, banner, toast + navigation, Cancel pristine/dirty, guard, Tab in SQL) + tests.
- [x] Toast service/host + tests; discard modal; tags-input `rejected` + test.

**Acceptance Criteria:**
- Given the form, when I enter SQL, title and description and submit, then the query is created (201) and I land on its detail.
- Given a missing title/description or an over-length field, when I submit, then the field error shows (client or 400 `field`) and nothing is saved; optional fields are collapsed by default.

## Spec Change Log

## Verification

**Commands:**
- `npx ng test --watch=false --browsers=ChromeHeadless` (CHROME_BIN=Edge 155) -- result: 91/91 SUCCESS, no warnings
- `npx ng build` -- result: success. Backend untouched.
