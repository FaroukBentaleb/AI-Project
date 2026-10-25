---
title: 'Story 7.3: Generation error states with a manual-save path'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'b080caa'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-1-generate-screen.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-2-save-generated-sql-short-form.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-2-clear-llm-failure-handling.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-3-corrective-retry-refusal-deadline.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/DESIGN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Generation failures show a message only (7.1). The user cannot see why SQL was rejected, cannot retry once the provider recovers, and has no path forward when the LLM is down.

**Approach:** Extend the 7.1 `GenerateError` view model with per-code actions and the rejected-SQL parts, then render them in the existing banner (FR30, EXPERIENCE.md Generate states): *Save a query manually* on every outage, *Retry in ~Ns* counting down from the retry hint, *Rephrase and retry* plus the rejected SQL and reasons on `SQL_REJECTED`. Manual save hands prefill 3 (prompt in Notes, *More options* expanded, `is_llm_generated = false`) to the Save form via the one-shot session prefill (`?from=manual`).

## Boundaries & Constraints

- Frontend only, inside `frontend/src/app/features/sql/`. No backend change.
- Rejected SQL has no Copy and no Save. The raw model reply is never shown (v1 assumption).
- Prefill never travels in the URL; consumed once. Generate session state (prompt, error) stays intact across the round trip.
- Never auto-save, never auto-retry.

</frozen-after-approval>

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| `LLM_UNAVAILABLE` 503 | no hint | Warning banner, *Save a query manually*, *Retry* enabled |
| `LLM_UNAVAILABLE` 503 | `retry_after_seconds: 3` | *Retry in ~3s* disabled, ticks each second, enabled "Retry" at 0; click re-posts the prompt |
| `LLM_QUOTA_EXCEEDED` 503 | `Retry-After: 20` header only | Warning banner, *Retry in ~20s*, *Save a query manually* |
| `LLM_NOT_CONFIGURED` 503 | — | Danger banner, *Save a query manually* only |
| Gateway timeout / network | 504 non-JSON, or status 0 | `LLM_UNAVAILABLE` copy and actions |
| `SQL_REJECTED` 422 | `rejected_sql`, `reasons[]` | Danger banner, *Rephrase and retry* (focuses prompt), rejected SQL block (no Copy/Save), each reason as code chip + text |
| `LLM_REFUSED` / `SQL_PARSE_ERROR` 422 | `llm_raw_response` | Warning banner "… Try rephrasing."; no actions; raw reply hidden |
| Manual save | click *Save a query manually* | `/sql/queries/new?from=manual`; Notes = prompt (≤ 2000), *More options* open, SQL/Title/Description empty, no original-prompt line |
| Save | fill Title, Description, SQL | POST `is_llm_generated: false`, `notes` = prompt, `original_nl_prompt: null`; 201 → toast rule (indexed / not yet indexed), open detail |
| Cancel / reopen | Cancel; later open `?from=manual` | Back to Generate with prompt and banner; reopen is blank |

## Decisions

- **Network failure (status 0) maps to `LLM_UNAVAILABLE`** like gateway 502/503/504 (story text "gateway timeout or network failure"), replacing 7.1's generic message for Generate only; other screens keep the generic copy. Envelope-less 500 stays generic.
- **Countdown model:** the error stores `retryAt` (epoch ms) at creation, so the countdown survives in-app navigation through session state. A 1 s interval runs only while seconds remain, started on error and on screen init, stopped at 0 and on destroy. Label "Retry in ~Ns" (disabled) → "Retry". Retry also respects `canSubmit` (not while generating).
- **Retry** only on `LLM_UNAVAILABLE` / `LLM_QUOTA_EXCEEDED` (and their mapped timeout/network cases); none on `LLM_NOT_CONFIGURED`. *Rephrase and retry* only on `SQL_REJECTED` (per EXPERIENCE.md states table); `LLM_REFUSED` / `SQL_PARSE_ERROR` rely on the message.
- **Rejected SQL details** read 6.3's shape: `details.rejected_sql` and `details.reasons[{code, reason}]`; rendered with the Validate code-chip pattern under a "Rejected SQL" heading.
- **Safety Note on `SQL_REJECTED` is not shown:** EXPERIENCE.md wants the server's `safety_note` there, but the 422 envelope does not carry it and the copy must stay server-owned. Deferred (backend could add `safety_note` to `GenerationErrorDetails`).
- **Manual prefill:** `SaveSource` adds `'manual'`; `prefill(draft, expandMore=true)` for that source; Cancel fallback `/sql/generate`. Notes are the session prompt capped at `SAVE_LIMITS.notes`. The header *+ New query* (no `from`) is unchanged.

## Code Map

- `generate/generate-error.ts` -- `retryAt`, `canSaveManually`, `canRetry`, `canRephrase`, `rejectedSql`, `reasons`, `retrySecondsLeft()`; status 0 → unavailable.
- `generate/generate.component.{ts,css}` -- banner actions, countdown ticker, rejected section, `saveManually()`.
- `core/session-state.service.ts` -- `SaveSource` `'manual'`.
- `save/save-query.component.ts` -- `?from=manual` expands *More options*, Cancel back to Generate.
- `generate/generate-errors.spec.ts` -- every state, countdown, manual save end to end; `generate.component.spec.ts` network test updated.

## Tasks & Acceptance

- [x] Extend the error view model with actions, retry deadline and rejected parts.
- [x] Render banner actions, countdown, rejected SQL + reasons; *Rephrase and retry* focuses the prompt.
- [x] Manual-save prefill 3 through session state; Save form expands *More options*, Cancel returns to Generate.
- [x] Tests: each 503 code + 504 + network, `SQL_REJECTED`, `LLM_REFUSED`, `SQL_PARSE_ERROR`, countdown (body and header hints), manual save end to end, one-shot + Cancel.
- [x] `ng test` and `ng build` green.

**AC:** Given each 503 code or a gateway timeout, the specific message is shown with *Save a query manually*. Given a 422 `SQL_REJECTED`, the rejected SQL and each reason are displayed. Given a 422 `LLM_REFUSED` or `SQL_PARSE_ERROR`, the message suggests rephrasing.

## Spec Change Log

## Verification

- `npx ng test --watch=false --browsers=ChromeHeadless` (CHROME_BIN = Edge): 195 / 195 SUCCESS.
- `npx ng build`: success.
