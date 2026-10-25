---
title: 'Story 3.2: Library screen with search, filters, and badges'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '504d215'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-1-sql-module-shell-identity-errors.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `/sql/library` is a placeholder, so developers cannot browse, search, or judge the team's queries in the browser.

**Approach:** Replace the placeholder with a Library screen (FR27) on top of `GET /api/sql/queries`: a search row (`q`, sort), a filter bar (tags, author, LLM-generated only, Deprecated Hide/Show), full-width query cards with the SQL inline and badges, and a pager. All view state lives in the query string.

## Boundaries & Constraints

**Always:** Work in `frontend/src/app/features/sql/`; follow the 3.1 conventions (standalone components, `SqlApiService`, `toUiError`, Midnight Terminal tokens on the shell host, Karma/Jasmine). Spines win over the epic and mockup.

**Never:** No Copy button or usage call (3.3), no save form or detail content (3.4/3.5), no backend or other-module changes, no syntax highlighting engine, no infinite scroll.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Open | `/sql/library` | `page=0&size=20&sort=created_at,desc&deprecated=false`; 3 aria-hidden skeleton cards and polite "Loading" while pending | — |
| Populated | rows returned | one card each: title link → detail, 2-line description, avatar · author · updated relative, tags, badges, SQL in monospace on code-bg | — |
| Long SQL | > 6 lines | first 6 lines, fade, *Show all (n lines)* → detail | — |
| Badges | `is_llm_generated` / `deprecated` / usage | "LLM-generated", "Deprecated", "Used n× · last <relative>" or "Never used" (text, not color) | — |
| Search | typing | debounced 300 ms; Enter applies at once; URL `q=`; page reset to 1 | — |
| Filters/sort | any change | written to URL; API gets AND-combined params; page reset to 1 | — |
| URL restore | `?q=&tags=a,b&author=&llm=true&deprecated=show&sort=title&page=2` | controls reflect it; API page=1 | malformed values → defaults |
| No results | 0 rows with filters | "No queries match '<q>'." (or "…these filters.") + *Clear filters* + *Generate "<q>"* | — |
| Empty library | 0 rows, no filters | "No queries yet." + *+ New query* + *Generate* | — |
| Load failed | any error | danger banner (`role=alert`) with mapped message + *Retry* | generic copy for network/unknown |
| Paging | total_pages > 1 | "21–23 of 23", prev/next, page buttons, current `aria-current` | — |

## Decisions

- URL params (1-based `page`, defaults omitted): `q`, `tags` (comma list), `author`, `llm=true`, `deprecated=show`, `sort=newest|most_used|recently_used|title`, `page`. API page = URL page − 1.
- Deprecated **Hide** (default) sends `deprecated=false`; **Show** omits the param (both live and deprecated, following the sort, not pinned). Hide is not a "filter" for *Clear filters* visibility.
- **Read-only filter and Not-read-only badge are deferred to Epic 4**: the API has no `read_only` field or filter yet (spec-1-4 Never). The story AC only requires LLM/deprecated/usage badges.
- `tags` sent comma-joined (backend `LibraryFilter` splits on commas); tags trimmed/lowercased/deduped, max 10.
- *Clear filters* resets `q` and all filters, keeps sort.
- *Generate "<q>"* navigates to `/sql/generate` with router `state: { prompt }` (no URL param, not submitted); Generate (Epic 7) reads it.
- SQL preview is plain monospace (no syntax colors yet); highlighting deferred to a later story.
- New shared primitives (`badge`, `tag`, `code-block`, `banner`, `empty-state`, `btn-sm`, `sr-only`) added to `shell/sql-theme.css`; reusable `sql-tags-input` in `shared/` for 3.4.
- Stale responses are cancelled with `switchMap`; *Retry* re-issues the current URL's request.

</frozen-after-approval>

## Code Map

- `features/sql/library/library-state.ts` -- URL ⇄ state ⇄ API param mapping, sort options, tag normalization.
- `features/sql/library/library.component.ts|css` -- screen, filter bar, states, pager.
- `features/sql/library/query-card.component.ts|css` -- card anatomy, badges, 6-line clip.
- `features/sql/library/relative-time.ts` -- "3 days ago".
- `features/sql/shared/tags-input.component.ts|css` -- chips input.
- `features/sql/sql.routes.ts` -- `library` → `LibraryComponent`.
- `features/sql/shell/sql-theme.css` -- shared primitives; `sql-shell.component.spec.ts` updated for the real Library.

## Tasks & Acceptance

**Execution:**
- [x] State mapping + tests.
- [x] Relative time + tests.
- [x] Tags input + tests.
- [x] Query card (badges, clip, links) + tests.
- [x] Library screen (loading, populated, no results, empty library, error/retry, paging, search debounce/Enter, filters, sort, URL restore) + tests.
- [x] Route wiring; shell spec updated.

**Acceptance Criteria:**
- Given saved queries exist, when I open the library, then I see 20 per page, newest first, each with its SQL visible without any click.
- Given a search term and filters, when I apply them, then the list reloads from the API with the combined filters and paging resets to page 1.
- Given a deprecated or LLM-generated query, then it shows the badge, and use count and last used are displayed.
- An empty result shows an empty state, not a blank screen.

## Spec Change Log

## Verification

**Commands:**
- `npx ng test --watch=false --browsers=ChromeHeadless` (CHROME_BIN=Edge 155) -- result: 58/58 SUCCESS
- `npx ng build` -- result: success (sql-module lazy chunk 57.5 kB). Backend untouched.
