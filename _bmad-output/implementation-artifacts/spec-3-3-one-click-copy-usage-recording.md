---
title: 'Story 3.3: One-click copy with usage recording'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '504d215'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-2-library-screen-search-filters-badges.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-8-record-usage-saved-query.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Developers can read a query in the Library but must select and copy it by hand, and the team never learns which queries are actually used.

**Approach:** A reusable `sql-copy-button` (FR28) on every query card: it copies the **full** SQL to the clipboard, flips to "Copied ✓" for ~2 s with a polite announcement, bumps the use count optimistically and calls `POST /api/sql/queries/{id}/usage` fire-and-forget. The same component is reused by detail (3.5) and Generate/Validate (no `queryId` → no usage).

## Boundaries & Constraints

**Always:** Work in `frontend/src/app/features/sql/`; follow the 3.1/3.2 conventions (standalone components, `SqlApiService`, Midnight Terminal tokens, `btn-sm` for card Copy per DESIGN.md, Karma/Jasmine with `HttpTestingController`). Spines win over the epic.

**Never:** No backend changes; no toast for copy (toasts are for mutations); no detail-screen content (3.5); no other-module changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Copy | click *Copy* on a card | clipboard = exact full `sql_content` (not the 6-line preview); button reads "Copied ✓" ~2 s; `role=status` polite "Copied"; badge "Used n+1×" at once; one `POST …/{id}/usage` | — |
| Usage fails | POST 4xx/5xx/network | copy and bumped count stay; nothing shown; `console.debug` only | silent |
| Usage slow | POST pending | confirmation does not wait for it | — |
| Clipboard denied/absent | `writeText` rejects or no `navigator.clipboard` | card un-clips, the full SQL is selected; message "Couldn't copy — press Ctrl/⌘+C" (`role=alert`); no usage, no bump | — |
| No query id | Generate/Validate reuse | copy only, no usage call | — |
| Repeat click | clicks again | each successful copy counts once; the 2 s timer restarts | — |

## Decisions

- Copy lives in a shared `CopyButtonComponent` (`shared/`) with inputs `text`, `queryId?`, `buttonText`, `label` and outputs `copied` / `failed`; the host owns the optimistic bump and the selection fallback.
- Placement: right of the card title (DESIGN.md card anatomy), `btn-sm btn-secondary`; accessible name "Copy SQL of <title>".
- Feedback is the in-button "Copied ✓" swap plus an sr-only polite status, not a toast (EXPERIENCE.md: toasts are post-mutation; "Copied" is listed as a polite announcement).
- Usage is recorded only after a successful clipboard write: a denied clipboard is not a use. The manual Ctrl+C after the fallback is not counted (cannot be observed).
- Optimistic bump sets `use_count+1` and `last_used_at=now` locally; a failed usage call does not roll it back (EXPERIENCE.md). A later refresh shows the server truth.
- The not-read-only warning banner on copy is deferred to Epic 4 (no `read_only` field in the API yet, same as 3.2).
- Copy on the detail screen ships with 3.5 (detail is still a placeholder); the component is ready for it.
- Test hygiene (3.2 follow-up): the shell spec appends `provideHttpClientTesting()` to the SQL route's own providers, so the real Library's request hits the in-memory backend (asserted via the route injector's `HttpTestingController`).

</frozen-after-approval>

## Code Map

- `features/sql/core/sql-api.service.ts` -- `recordUsage(id)` (POST, null body, 204).
- `features/sql/shared/copy-button.component.ts` -- clipboard, Copied state, live region, fallback message, fire-and-forget usage.
- `features/sql/library/query-card.component.ts|css` -- title row with Copy, optimistic bump, un-clip + select on clipboard failure.
- `features/sql/shell/sql-shell.component.spec.ts` -- no network in tests.

## Tasks & Acceptance

**Execution:**
- [x] `recordUsage` + test.
- [x] Copy button (copy success, Copied swap and reset, polite status, usage call, usage failure silent, non-blocking, no id, clipboard fallback, a11y) + tests.
- [x] Card integration (full SQL copied, optimistic bump kept on usage failure, fallback un-clips and selects) + tests.
- [x] Shell spec uses the testing HTTP backend.

**Acceptance Criteria:**
- Given a listed query, when I click Copy, then the exact SQL is on my clipboard, a "Copied" confirmation appears, and use count increases on refresh.
- Given the usage call fails, when I click Copy, then the SQL is still copied and no error blocks me.

## Spec Change Log

## Verification

**Commands:**
- `npx ng test --watch=false --browsers=ChromeHeadless` (CHROME_BIN=Edge 155) -- result: 68/68 SUCCESS
- `npx ng build` -- result: success. Backend untouched.
