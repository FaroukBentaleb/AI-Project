---
title: 'Story 7.2: Save generated SQL through the short form'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'b080caa'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-1-generate-screen.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-4-save-query-manually.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-5-validate-screen-read-only-signals.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/DESIGN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A good generated query can only be copied; it cannot join the library, so it never improves future generations.

**Approach:** A *Save to library* action next to Copy on the Generate SQL tab hands prefill 1 (SQL, original prompt, `is_llm_generated = true`) to the Story 3.4 Save form through the one-shot session prefill (`?from=generate`, same pattern as `?from=validate`). The user types a title and description; optional fields stay collapsed. The post-mutation toast rule reports indexed / not yet indexed.

## Boundaries & Constraints

- Frontend only, inside `frontend/src/app/features/sql/`. No backend change; create API limits apply (`original_nl_prompt` ≤ 1000, notes ≤ 2000).
- Never auto-save; Save is not offered on rejected SQL (errors are not results, so no button).
- The prefill never travels in the URL; it is consumed once.
- Generate session state (prompt, result) stays intact across the round trip.
- Error states and the manual-save path (prefill 3) are Story 7.3.

</frozen-after-approval>

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| Open form | Generate 200 result, click *Save to library* | `/sql/queries/new?from=generate`; SQL prefilled; Title/Description empty; read-only "Original prompt" line; *More options* collapsed |
| Save | Title + Description, submit | POST with `is_llm_generated: true`, `original_nl_prompt` = prompt; 201 → toast "Saved · added to team examples", open detail |
| Not indexed | 201 with "Not yet indexed…" Warning | Warning toast "Saved · not yet indexed (team examples unavailable)" |
| One-shot | Reopen `?from=generate` later | Blank form |
| Cancel | Untouched form | Back to Generate; prompt and result still shown |
| Server 400 | `field` not a form field (e.g. `original_nl_prompt`) | Form-level danger banner, stays on form, no toast |
| Missing echo | `original_prompt` empty in response | Falls back to session prompt; capped at 1000 |

## Decisions

- **Original prompt source:** the server echo `original_prompt`, falling back to the session prompt; truncated to `SAVE_LIMITS.original_nl_prompt` (1000). Shown as a read-only line above *More options* only when `is_llm_generated` is set (create mode).
- **Cancel fallback:** with no in-app history entry, Cancel from `?from=generate` goes to `/sql/generate` (session state restores the result); other entry points keep `/sql/library`.
- **Save button:** `btn-sm btn-secondary`, label "Save to library" (same as Validate), disabled while a new generation is running.
- **Server 400 on non-form fields** (`original_nl_prompt`, `is_llm_generated`) uses the existing form-level banner; no new field UI.
- **Placeholder removed:** `shell/placeholder.component.ts` deleted; nothing referenced it after 7.1.

## Code Map

- `core/session-state.service.ts` -- `SaveSource` adds `'generate'`.
- `generate/generate.component.ts` -- *Save to library* button + `saveGenerated()`.
- `save/save-query.component.ts|.css` -- original-prompt line; Cancel fallback per source.
- `generate/save-generated.spec.ts` -- end-to-end Generate → Save tests.
- `shell/placeholder.component.ts` -- deleted.

## Tasks & Acceptance

- [x] Add `'generate'` save source and the Generate *Save to library* action with prefill 1.
- [x] Show the original prompt read-only on the Save form; Cancel returns to Generate.
- [x] Tests: prefill contents, one-shot consumption, success toast + navigation, not-indexed toast, cancel with state intact, 400 mapping, prompt fallback.
- [x] Delete the unused placeholder component.

**AC:** Given a successful generation, when I click Save and enter a title and description, then the query is created with `is_llm_generated = true` and `original_nl_prompt` set, and I see it was added to team examples. Given an indexing Warning in the 201, I see it was saved but not yet indexed.

## Spec Change Log

## Verification

- `npx ng test --watch=false --browsers=ChromeHeadless` (Edge): 183 / 183 SUCCESS.
- `npx ng build`: success.
