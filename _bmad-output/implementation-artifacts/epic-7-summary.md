# Epic 7 summary: Generate in the Browser (Angular)

Developers generate SQL from a plain-English prompt at `/sql/generate`, see Warnings, the Safety Note and the Examples used, and save the result through the short prefilled form. Every generation failure is explained, and every outage leads to a manual save. All code lives in `frontend/src/app/features/sql/` (mainly `generate/`, plus small changes in `core/` and `save/`). No backend change.

Final verification (Story 7.3 run): `npx ng test --watch=false --browsers=ChromeHeadless` with headless Edge gave 195 / 195 SUCCESS; `npx ng build` succeeded.

## Delivered

- **7.1 Generate screen:** command bar (1-1000 counter, Ctrl/⌘+Enter, personal-data notice), Generating state with the previous result dimmed, ARIA result tabs SQL · Examples (n) · Warnings (n), Copy without usage, server Safety Note outside the tabs, Library handoff via `history.state.prompt`, session-state persistence. The post-save toast now keys "not yet indexed" on the server's "Not yet indexed" Warning only.
- **7.2 Save generated SQL:** *Save to library* next to Copy; prefill 1 (SQL, original prompt read-only, `is_llm_generated = true`) via one-shot session prefill (`?from=generate`); Cancel returns to Generate with state intact.
- **7.3 Error states and manual save:** per-code banners from the EXPERIENCE.md copy map; *Save a query manually* on `LLM_UNAVAILABLE`, `LLM_QUOTA_EXCEEDED`, `LLM_NOT_CONFIGURED`, gateway timeout and network failure; *Retry in ~Ns* countdown from `retry_after_seconds` / `Retry-After`; `SQL_REJECTED` shows the rejected SQL (no Copy/Save) and each reason with *Rephrase and retry*; `LLM_REFUSED` / `SQL_PARSE_ERROR` suggest rephrasing. Prefill 3 (`?from=manual`): prompt in Notes, *More options* expanded, `is_llm_generated = false`.

## Deferred / risks

- **Safety Note on a rejected generation** is not shown: the 422 envelope has no `safety_note`, and the copy is server-owned. Adding it to `GenerationErrorDetails` would let the frontend render it with no other change.
- Network failures on Generate now read as "temporarily unavailable" (story text); other screens keep the generic message.
- End-to-end generation still depends on the Epic 6 model-availability blocker (`epic-6-summary.md`); the screen was verified with unit tests against mocked HTTP only, not against a live model.
- The raw model reply (`llm_raw_response`) is never displayed (v1 assumption in EXPERIENCE.md).
