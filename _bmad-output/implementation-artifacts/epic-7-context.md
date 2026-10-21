# Epic 7 Context: Generate in the Browser (Angular)

<!-- Compiled from planning artifacts (epics-sql-service.md Epic 7, ux-sql-service EXPERIENCE.md + DESIGN.md), epic-3/4/6 summaries. Edit freely. -->

## Goal

Developers generate SQL from a plain-English prompt in DevAssist Hub, see the Warnings, the Safety Note and the Examples used, and save the result through the short prefilled form. Every generation failure leads to a manual save. All work lives in `frontend/src/app/features/sql/`.

## Stories

- 7.1: Generate screen (prompt, loading, success result: SQL + copy, Warnings, Safety Note, Examples links)
- 7.2: Save generated SQL through the short form (prefill 1: SQL + original prompt, is_llm_generated true)
- 7.3: Generation error states and the manual-save path (422/503/timeout banners, retry countdown, prefill 3)

## Requirements & Constraints

- FR29 (generate screen + save form), FR30 (generation errors). Backend: `POST /api/sql/generate` `{prompt (1-1000), dialect?}` → 200 `{sql, dialect, source, original_prompt, examples_used[{id,title}], warnings[], safety_note, valid, prompt_version}`. Errors: 422 `SQL_REJECTED` (details = rejected SQL + rejections), `SQL_PARSE_ERROR`, `LLM_REFUSED`; 503 `LLM_UNAVAILABLE` (+ `retry_after_seconds` / `Retry-After`), `LLM_QUOTA_EXCEEDED`, `LLM_NOT_CONFIGURED`; 400 `VALIDATION_FAILED`. Gateway route timeout 15 s (504 from the gateway).
- Server warning texts: FR-15 no-similar, FR-16 RAG-unavailable (verbatim). Save responses carry indexing Warnings starting "Not yet indexed" plus validator Warnings.
- Command bar: 1-1000 chars with live counter, Ctrl/⌘+Enter, "Generating…" + read-only prompt + indicator "Finding similar team queries, then generating…", previous result dimmed. Personal-data notice always visible.
- Result tabs (success only): SQL · Examples (n) · Warnings (n), ARIA tabs with automatic activation, reset to SQL on a new result, Warnings label warning-colored when n > 0. Safety Note outside the tabs, verbatim from the server. Examples are title-only links to detail in the same tab.
- Session state (`SqlSessionState`): prompt and result survive in-app navigation, cleared on a full reload. Library's *Generate "<q>"* passes the term via router `history.state.prompt` (prefilled, not submitted).
- Never auto-save, never execute; never claim "safe".

## Technical Decisions

- Same conventions as Epics 3-4: standalone components, signals, `SqlApiService`, `toUiError`, scoped Midnight Terminal CSS (`shell/sql-theme.css`), Karma/Jasmine with `HttpTestingController`, headless Edge via `CHROME_BIN`.
- Validate screen (4.5) is the closest analogue; Save prefill goes through `SqlSessionState.offerPrefill`, never the URL.

## Dependencies

- 7.1 needs 6.4, 3.3. 7.2 needs 7.1, 3.4. 7.3 needs 7.1, 7.2, 6.2, 6.3.
