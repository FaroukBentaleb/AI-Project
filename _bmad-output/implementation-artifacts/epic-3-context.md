# Epic 3 Context: Team Library in the Browser (Angular)

<!-- Compiled from planning artifacts (epics-sql-service.md Epic 3, ux-sql-service EXPERIENCE.md + DESIGN.md). Edit freely. -->

## Goal

Developers use the Team Library inside DevAssist Hub through the gateway: a display-name identity, a library list with inline SQL and badges, search and filters, one-click copy, a short manual save form, and a detail/edit screen with deprecate, undeprecate and delete. All work lives in `frontend/src/app/features/sql/`.

## Stories

- 3.1: SQL module shell, identity, and error handling
- 3.2: Library screen with search, filters, and badges
- 3.3: One-click copy with usage recording
- 3.4: Save a query manually
- 3.5: Query detail, edit, deprecate, and delete

## Requirements & Constraints

- FR27 library, FR28 copy, FR30 (library errors), FR31 detail/edit, FR33 identity.
- Every call goes through the gateway under `/api/sql/**` (`environment.apiBaseUrl` + `/sql`); CORS is handled by the gateway. Every SQL request carries `X-User-Id` = stored display name.
- API is snake_case JSON; error envelope `{error, message, field, details}`; list envelope `{content, page, size, total_elements, total_pages}`; PATCH uses `version` (409 `CONFLICT`).
- The module never executes SQL; no schema-hint field anywhere.

## Technical Decisions

- Angular 18 standalone components, lazy-loaded via the existing `SqlModule` (`/sql`). No UI library; hand-rolled CSS scoped to SQL components (Midnight Terminal tokens from DESIGN.md, dark only). Do not touch other teams' modules or global styles.
- Routes (EXPERIENCE IA): `/sql/library` (default), `/sql/queries/new`, `/sql/queries/:id`, `/sql/queries/:id/edit`, `/sql/generate`, `/sql/validate`.
- Module header on every SQL screen: tabs Library · Generate · Validate (Detail/Save/Edit keep Library active), *+ New query*, user chip with menu → *Change display name* (ARIA menu button).
- Identity modal: "What should we call you?", trimmed 1–100 chars, stored in `localStorage`; not dismissible on first visit, cancelable when changing.
- Error-copy contract: client map by `error` code → else server `message` → else "Service unavailable. Try again in a moment." Unknown code, non-JSON body, or network failure → generic. `VALIDATION_FAILED` shows the server message inline on `field`.
- Library state (q, filters, sort, page) lives in the query string; 20 per page; SQL preview clips at 6 lines with *Show all* (spine wins over "scroll if long").
- Copy copies the full SQL, flips to "Copied ✓" ~2 s, bumps use count optimistically, calls `POST …/usage`; usage failure is silent.
- Post-mutation toast rule keyed on `warnings[]`, `read_only`, `deprecated` (see EXPERIENCE.md).
- A11y: focus ring on `:focus-visible`, modals `role="dialog"` with focus trap and focus restore, Esc closes topmost modal/menu (not the first-visit identity modal), no status by color alone, `prefers-reduced-motion` respected.
- Tests: Karma + Jasmine headless (Chromium-family browser via `CHROME_BIN`).

## Dependencies

- 3.1 needs Story 1.2. 3.2 needs 3.1, 1.4, 1.7, 1.8. 3.3 needs 3.2, 1.8. 3.4 needs 3.1, 1.2. 3.5 needs 3.3, 3.4, 1.5–1.7.
