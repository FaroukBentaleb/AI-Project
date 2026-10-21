# Epic 3 summary: Team Library in the browser (Angular)

Everything lives in `frontend/src/app/features/sql/` (lazy `SqlModule` at `/sql`, module-scoped HttpClient, Midnight Terminal CSS scoped to SQL components). No other module and no global style was touched. Final verification: `ng test` 128/128 (Edge headless), `ng build` clean.

## Delivered

- **3.1 Shell, identity, errors:** module header (Library · Generate · Validate, *+ New query*, user chip menu), display-name identity modal (`localStorage`, sent as `X-User-Id` by an interceptor), error-copy contract `toUiError` (client map → server message for `VALIDATION_FAILED` → generic).
- **3.2 Library:** search, filters (tags, author, LLM-only, deprecated Hide/Show), sort, and 20-per-page paging, all in the query string. Query cards have the inline SQL clipped at 6 lines, badges, a skeleton, empty/no-results states, and Retry on failure.
- **3.3 Copy:** one-click copy of the full SQL, "Copied ✓" for ~2 s, an optimistic use count, fire-and-forget `POST …/usage`, and a select-for-manual-copy fallback.
- **3.4 Save:** manual Save form (required Title/Description/SQL with counters, collapsed *More options*, 400 field mapping, Ctrl/⌘+Enter, discard guard + `beforeunload`), the post-mutation toast rule, and a reusable toast and discard modal.
- **3.5 Detail, edit, deprecate, delete:** the detail screen with every FR31 attribute (origin + original prompt, usage, notes, team-example status, neutral deprecated banner with the replacement link) and a line-number gutter. Edit runs the Save form in edit mode (`PATCH` of the changed fields + `version`; on a 409, *Reload* merges the latest version under your changed fields, with "Your change" markers). The Deprecate modal has a required reason (/500) and a Replaced-by combobox. Undeprecate is included. Delete is confirmed in a modal and returns to the last Library URL with a toast (404 → "Already deleted"). The 3.4 Cancel fix: an `InAppHistory` service goes Back only when the previous entry is in-app, otherwise it falls back to the Library (create) or the detail page (edit).

## Deferred

- **Epic 4 (validator):** the *Read-only ✓* / *Not read-only* badges, the read-only aside row, the not-read-only danger banner with reason chips, and the copy warning for not-read-only queries. The UI already honours `read_only` when the API returns it (toast rule, team-examples row), and the Validate screen is still a placeholder.
- **Epic 5 (indexing):** the real team-example status. The detail screen shows "Not yet indexed…" only when the response has `indexed=false`; otherwise it shows "Used as a team example".
- **Epic 7 (Generate / outage flows):** the Generate screen, the module session-state service, and Save prefill by entry point (the `SaveQueryComponent.prefill()` hook exists). The Library's *Generate "<q>"* link already passes `{prompt}` in the navigation state, but nothing reads it yet.
- Not done in v1: syntax highlighting in code blocks, and a backend limit for the 500-character deprecation reason (UI-only for now).
