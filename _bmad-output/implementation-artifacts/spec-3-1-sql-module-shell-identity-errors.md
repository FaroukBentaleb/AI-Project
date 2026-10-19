---
title: 'Story 3.1: SQL module shell, identity, and error handling'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '504d215'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The `/sql` route in the Angular app is an empty lazy module: nothing attributes requests to a person, and there is no consistent way to turn API failures into readable messages.

**Approach:** Give the SQL module a shell (module header + routed placeholder screens), an identity service backed by `localStorage` with a first-visit identity modal, a functional HTTP interceptor that adds `X-User-Id` to `/api/sql/**` calls only, an `SqlApiService` rooted at the gateway's `/api/sql`, and a pure error mapper implementing the EXPERIENCE.md error-copy contract (FR30 library part, FR33).

## Boundaries & Constraints

**Always:** Work inside `frontend/src/app/features/sql/`; follow the existing Angular 18 standalone + lazy `SqlModule` pattern; Midnight Terminal tokens scoped to SQL components; all SQL calls through `environment.apiBaseUrl + '/sql'`.

**Never:** No change to other features, `main.ts`, global styles, or the backend/gateway (gateway already routes `/api/sql/**` and handles CORS). No library list, copy, save form, or detail content (Stories 3.2–3.5). The interceptor never touches non-SQL URLs.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First visit | no stored name | Identity modal, not dismissible (no Cancel, Esc ignored); child screen not rendered until a name is saved | — |
| Save name | "  sami  " | stored "sami"; header chip reads "sami" | blank or >100 chars → *Continue* disabled |
| Change name | chip menu → *Change display name* | modal prefilled, cancelable; new name used by subsequent requests | — |
| SQL request | name set, URL under `/api/sql/` | `X-User-Id: <name>` | — |
| Other request | `/api/bugs/...` | header not added | — |
| Storage unavailable | `localStorage` throws | name kept in memory for the session | never crashes |
| Mapped code | `{error:"NOT_FOUND",...}` | "This query doesn't exist or was deleted." | — |
| Validation | `{error:"VALIDATION_FAILED",message:"m",field:"title"}` | message "m", field "title" | — |
| Unknown code / non-JSON / network (status 0) / missing envelope | anything else | "Service unavailable. Try again in a moment." | — |

## Decisions

- Interceptor is provided at the SQL route level (`provideHttpClient(withInterceptors(...))` in the shell route's `providers`), so the app-wide `HttpClient` in `main.ts` is untouched; it is additionally URL-scoped to `${apiBaseUrl}/sql/` as a safety net.
- Child screens render only after a name exists, so no request leaves the module as `anonymous`.
- "Known code" = a code in the client message map, plus `VALIDATION_FAILED`, whose server `message` is shown (inline on `field` by later stories). Any other code (incl. `INTERNAL_ERROR`, `METHOD_NOT_ALLOWED`) falls to the generic message; this reconciles the spine's rule 2 ("server message") with its "unknown code → generic" row and the epic AC.
- The mapper is context-free in 3.1; gateway-timeout → LLM copy for Generate is deferred to Epic 7.
- Generate, Validate, Save, Detail, Edit are registered as routed placeholders so the header tabs and *+ New query* work now; later stories replace them.
- `localStorage` key: `devassist.sql.displayName`.
- Frontend had no test runner: add Karma + Jasmine (Angular 18 defaults) and an `ng test` target; headless run uses a Chromium-family browser via `CHROME_BIN` (Edge on this machine).

</frozen-after-approval>

## Code Map

- `features/sql/sql.routes.ts` -- shell route with providers, children (library default, queries/new, queries/:id, queries/:id/edit, generate, validate).
- `features/sql/core/identity.service.ts` -- signal-based display name, validation, storage.
- `features/sql/core/user-id.interceptor.ts` -- functional interceptor.
- `features/sql/core/sql-api.service.ts` -- base URL, `listQueries`, `getQuery`, typed models (`models.ts`).
- `features/sql/core/error-mapper.ts` -- `toUiError(err)` + message map.
- `features/sql/shell/sql-shell.component.ts` -- module header, tabs, user chip/menu, outlet gating.
- `features/sql/shell/identity-modal.component.ts` -- dialog with focus trap.
- `features/sql/shell/placeholder.component.ts` -- per-route placeholder.
- `frontend/angular.json`, `package.json`, `tsconfig.spec.json`, `karma.conf.js` -- test target.

## Tasks & Acceptance

**Execution:**
- [x] Test tooling (karma/jasmine, `test` target, `npm test` script).
- [x] Identity service + tests.
- [x] Interceptor + tests (header added, non-SQL untouched, name change reflected).
- [x] Error mapper + tests (every matrix error row).
- [x] SqlApiService + tests (URLs, params).
- [x] Shell, identity modal, placeholders, routes; component tests for first-visit gating and change-name.

**Acceptance Criteria:**
- Given no stored name, when I open `/sql`, then the identity modal asks once, stores the name, and SQL requests carry it as `X-User-Id`.
- Given a stored name, when I change it from the header, then subsequent requests use the new value.
- Given a known `error` code, or a non-JSON/network failure, then the mapped message or "Service unavailable. Try again in a moment." is produced.

## Implementation Notes

- `@angular/forms` is not a dependency of the app, so the identity modal binds the input with `[value]`/`(input)` instead of `ngModel`.
- Test tooling added as devDependencies: karma 6.4, jasmine-core 5.2, karma-chrome-launcher/coverage/jasmine/html-reporter, `@angular/platform-browser-dynamic` (needed by the karma builder); `package-lock.json` created by `npm install`.
- Theme tokens are CSS custom properties on the shell host (`sql-shell.component.css`); shared primitives in `shell/sql-theme.css`. Nothing global.

## Spec Change Log

## Verification

**Commands:**
- `npx ng test --watch=false --browsers=ChromeHeadless` (CHROME_BIN=Edge 155) -- result: 29/29 SUCCESS
- `npx ng build` -- result: success (sql-module lazy chunk 20 kB). No lint target configured; backend untouched.
