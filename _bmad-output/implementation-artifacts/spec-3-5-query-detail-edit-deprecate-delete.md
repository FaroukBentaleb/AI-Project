---
title: 'Story 3.5: Query detail, edit, deprecate, and delete'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: 'cb07b38'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-4-save-query-manually.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-5-edit-saved-query.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-6-soft-delete-saved-query.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-7-deprecate-restore-saved-query.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A developer who inherits a query can't see all of its details, fix it, withdraw it, or remove it from the browser.

**Approach:** Replace the `/sql/queries/:id` and `/sql/queries/:id/edit` placeholders. Detail (FR31): breadcrumb, title, badges, actions (Copy SQL · Edit · Deprecate/Undeprecate · Delete), the full SQL with a line gutter, and a metadata aside. Edit reuses the 3.4 Save form in edit mode and sends `PATCH` with `version`. A 409 shows the conflict banner, and *Reload* merges the latest version under the user's changed fields. Deprecating opens a modal with a required reason and an optional Replaced-by picker. Delete asks for confirmation, then returns to the Library with a toast. This story also fixes 3.4's Cancel so it never leaves the app.

## Boundaries & Constraints

**Always:** Work in `frontend/src/app/features/sql/`. Follow 3.1–3.4 conventions and reuse their pieces (`save-form.ts` rules, toast, discard modal, copy button, tags input, theme, `toUiError`). Spines win over the epic.

**Never:** No backend changes. No read-only reasons or badges (Epic 4), no indexing status beyond what the response carries (Epic 5), no session-state prefill (Epic 7). No changes to other modules or to global styles.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Detail load | GET `/queries/{id}` | Skeleton (`aria-hidden`, polite "Loading"), then breadcrumb `Library › title`, badges, actions, SQL, aside (description, author, created · updated, dialect, tags, origin + original prompt, usage, notes, team examples) | 404 → NOT_FOUND copy + *Back to Library*; other → danger banner + *Retry* |
| Deprecated | `deprecated=true` | Neutral banner "Deprecated: replaced by '<link>' — reason: …" (link only when `replaced_by` is set), *Deprecated* badge, *Undeprecate*, team examples "Not used as a team example" | Replacement lookup fails → link text "query #N"; 404 → no link |
| Copy | *Copy SQL* | copy button from 3.3 (usage recorded, use count bumps) | silent usage failure |
| Deprecate | reason (required, ≤ 500) + optional replacement | `PUT …/deprecation {reason, replaced_by}`; "Deprecating…"; toast "Deprecated · removed from team examples"; banner updates; focus back on the action | 400 on `reason` / `replaced_by` inline; 404 / other inline in the modal; the modal stays open |
| Picker | ≥ 2 chars | `GET /queries?q=…&deprecated=false`, this query excluded, 8 results; ↑/↓, Enter picks, Esc closes the list only; "Searching…", "No matching queries", removable chip | search failure → "No matching queries" |
| Undeprecate | click | `DELETE …/deprecation`; disabled "Undeprecating…"; toast per the post-mutation rule ("Undeprecated…") | 404 → 404 state; other → page banner |
| Delete | click → confirm | modal "Delete '<title>'?", focus on Cancel; "Deleting…"; 204 → last Library URL + "Deleted '<title>'" | 404 → Library + "Already deleted"; other → inline in the modal; Cancel/Esc does nothing |
| Edit load | `/edit` | skeleton, then the prefilled form, the Edit notice, breadcrumb `Library › title › Edit`, *Save changes*, More options label summarizing values | 404 → detail 404 treatment |
| Edit save | changes | `PATCH` with the changed fields plus `version`; 200 → detail + post-mutation toast | 400 field mapping as in 3.4 |
| 409 | stale `version` | assertive danger banner (CONFLICT copy) + *Reload* | — |
| Reload | click | GET latest; untouched fields take server values; changed fields keep the user's value with "Your change" (or "Your change — also changed by someone else" plus the server value); the next save uses the new `version` | 404 → 404 state, user SQL kept selectable |
| Edit 404 on save | row deleted | 404 treatment, user SQL shown in a code block | — |
| Cancel | Save or Edit | back only if the previous history entry is in-app (this session); otherwise Library (create) or detail (edit) | dirty → discard modal (3.4) |

</frozen-after-approval>

## Decisions

- **Edit is the Save form in edit mode** (`route.data.mode = 'edit'`), not a second component, so the rules, counters, 400 mapping, guard and Tab handling stay identical.
- **PATCH sends only the changed fields** (diff against the loaded or reloaded version) plus `version`. Clearing notes sends `""` (null means unchanged, per 1.5). `is_llm_generated` and the original prompt can't be edited.
- **Merge on Reload** is a pure function (`mergeOnReload(base, mine, latest)`) in `save-form.ts`. A field counts as "yours" when `mine ≠ base` and `mine ≠ latest`. The marker reads "also changed by someone else" when `latest ≠ base`. Markers stay until the save succeeds.
- **In-app back** (3.4 fix): `InAppHistory` gives each history entry a depth, keyed by the router `navigationId`. Pushes add 1, replaces keep the depth, popstate restores the stored depth, and entries from before the module loaded count as depth 0. Cancel goes back only when depth > 0; otherwise it navigates to the fallback. The service also remembers the last Library URL for the breadcrumb and after a delete.
- Modals are presentational (`busy`, `error` inputs). The detail screen owns the API calls. A shared `trapTab` handles the focus trap, and focus goes back to the trigger on close.
- The deprecation reason limit of 500 is UI-only (spine `[ASSUMPTION]`; the API allows 2000).
- Team-examples row: deprecated or `read_only=false` → "Not used as a team example"; `indexed=false` (when the API adds it) → "Not yet indexed — will be added automatically."; otherwise "Used as a team example". The read-only row and the not-read-only banner are deferred to Epic 4, because the API has no `read_only` yet.
- Edit save lands on detail by push, the same as create in 3.4.
- No syntax highlighting. The detail code block gets an `aria-hidden` line-number gutter only.

## Code Map

- `core/sql-api.service.ts` -- `patchQuery`, `deleteQuery`, `deprecateQuery`, `restoreQuery`.
- `core/in-app-history.service.ts` -- depth tracking, `back(fallback)`, `lastLibraryUrl`.
- `save/save-form.ts` -- `draftFromQuery`, `toPatchRequest`, `mergeOnReload`, `moreSummary`.
- `save/save-query.component.ts|css` -- edit mode, conflict banner, markers, 404, Cancel fix.
- `detail/query-detail.component.ts|css`, `detail/deprecate-modal.component.ts`, `detail/replaced-by-picker.component.ts`, `detail/delete-modal.component.ts`.
- `shared/focus-trap.ts`; `sql.routes.ts`; `shell/sql-shell.component.ts` (starts history tracking).

## Tasks & Acceptance

**Execution:**
- [x] API mutations + tests.
- [x] InAppHistory + tests; Cancel fix in the Save form + test for the "re-entered via Back" case.
- [x] Form model (draft from query, patch body, merge, summary) + tests.
- [x] Edit mode (load, 404, save, 400, 409 + Reload merge, markers, Cancel fallback to detail) + tests.
- [x] Detail (loading, 404, retry, default aside, LLM origin, deprecated banner + link, copy) + tests.
- [x] Deprecate modal + picker (with/without replacement, 400s inline, Esc), Undeprecate, Delete (confirm, cancel, 404) + tests.

**Acceptance Criteria:**
- Given a Saved Query, when I open its detail, then every FR31 attribute is visible, including the original prompt when it is LLM-generated.
- Given I edit and another user saved first, when I submit, then I see the conflict message, and Reload keeps my edits in view.
- Given I deprecate with a reason and a replacement, when I confirm, then the detail shows the reason and the replacement link, and the list shows the badge.
- Given I click Delete, when I confirm, then the query is deleted and I return to the Library. Cancelling does nothing.

## Spec Change Log

## Verification

**Commands:**
- `npx ng test --watch=false --browsers=ChromeHeadless` (CHROME_BIN=Edge 155) -- result: 128/128 SUCCESS, no warnings
- `npx ng build` -- result: success, no warnings. Backend untouched.
