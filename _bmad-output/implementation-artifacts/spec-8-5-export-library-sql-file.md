---
title: 'Story 8.5: Export the library as a .sql file (Could)'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'c2e849f'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-8-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-3-browse-team-library.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-4-search-filter-library.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-7-deprecate-restore-saved-query.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-4-read-only-flag-saved-queries.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-2-library-screen-search-filters-badges.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-5-validate-screen-read-only-signals.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/DESIGN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The library only lives in sql-service; teams that want queries next to their code have no way to take them out.

**Approach:** FR35. `GET /api/sql/queries/export` takes the same filters and sort as the list and returns `text/plain` as a `.sql` attachment: each query is a `--` comment header (title, description, author, tags, read-only, deprecated note) followed by its SQL. An Export button on the Library screen downloads exactly the filtered results.

## Boundaries & Constraints

- Same filter parsing as the list (`LibraryFilter`, `LibraryPaging` sort whitelist); invalid values → 400 `VALIDATION_FAILED`.
- Deleted queries are never exported (the shared search always adds `deleted = false`).
- The service never executes the SQL; the file is text only.
- Comment content cannot break out of its comment; statements are separated unambiguously.

</frozen-after-approval>

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| Filtered export | `?tags=reports&author=bob` | Only matching live queries, list order |
| All pages | > 1 page of results | All matches (paging params ignored) |
| Deleted row | Matches filters but deleted | Never in the file |
| Newline in metadata | Title `t\nDROP TABLE x;` | One `-- Title: t DROP TABLE x;` line |
| Block delimiters | `a */ b /* c` | `a * / b / * c` |
| SQL ends in `-- note` | `SELECT 1 -- note` | `;` on its own next line |
| SQL already `;`-terminated | `SELECT 1;` | Kept as is, no extra `;` |
| No matches | `?q=zzz` | Preamble with `0 queries`, 200 |
| Bad filter | `?deprecated=maybe` | 400 `VALIDATION_FAILED`, field `deprecated` |
| Export fails (UI) | 5xx / network | Danger banner "Export failed: …" with Dismiss; button re-enabled |

## Decisions

- **Format:** `--` line comments (the epic's wording) rather than `/* */` blocks: one `-- Label: value` line per field, so line terminators (CR, LF, NEL, U+2028/2029, other control chars) are collapsed to a space — no value can start a SQL line. `/*` and `*/` are still neutralised (repeat until stable) so no tool can read a block comment out of a header. Fields: Title, Description, Author, Tags (comma list, blank if none), Read-only (`yes`/`no`/`unknown` for null), Deprecated (only when deprecated: reason plus `(replaced by #id)`, or `yes`). Notes and NL prompt are not exported (not in the epic list).
- **Statement separation:** SQL is right-trimmed; if it does not end with `;` on a line free of `--`/`/*`, a `;` is appended on its own line. A blank line precedes each header. A two-line preamble states the count and that the file was not executed.
- **Endpoint:** in `SavedQueryController` (literal `/export` wins over `/{id}`); `text/plain;charset=UTF-8`, `Content-Disposition: attachment; filename="sql-library-YYYY-MM-DD.sql"` (UTC date), `X-Content-Type-Options: nosniff`. No `page`/`size`; `SavedQueryService.exportAll` walks the existing search in 500-row batches with the list sort (id tie-breaker keeps batches stable). No upper cap — library size is team-scale.
- **UX (not covered by EXPERIENCE.md/DESIGN.md — designed minimally):** a secondary button "Export .sql" at the end of the search/sort row; label "Exporting…", disabled and `aria-busy` while in flight. Failure shows a `role="alert"` danger banner under the row ("Export failed: <mapped message>") with a Dismiss ghost button. Export sends the current filters and sort (minus paging), so it always matches what the list shows (including the default "deprecated hidden").
- **Download:** `HttpClient` blob + object URL + hidden `<a download>`; filename from `Content-Disposition` only if it is a safe `[\w.-]+\.sql` basename, else `sql-library.sql` (also the fallback if a cross-origin gateway hides the header).

## Code Map

- `sql-service/.../service/SqlExportWriter.java` -- rendering and escaping.
- `sql-service/.../service/SavedQueryService.java` -- `exportAll(filter, sort)`.
- `sql-service/.../controller/SavedQueryController.java` -- `GET /export`.
- `frontend/.../core/sql-api.service.ts` -- `exportQueries()`.
- `frontend/.../library/library.component.ts|.css` -- button, banner, `exportFilename`, `saveBlob`.

## Tasks & Acceptance

- [x] `SqlExportWriter` + unit tests (header block, deprecated note, newline collapse, `*/`/`/*` neutralisation, trailing-comment terminator, empty).
- [x] `GET /api/sql/queries/export`; WebMvc test (content type, `Content-Disposition` filename, nosniff, filter + sort passthrough, default sort, 400) and Testcontainers IT (deleted excluded, filters honoured, order, escaping, 0 matches, 400).
- [x] Library Export button; frontend tests (params without paging, blob download with server filename, fallback filename, error banner + dismiss + retry, double-click guard, filename sanitising).

**AC:** Given filtered results, when I click Export, then a `.sql` file downloads containing exactly those queries, each preceded by its comment header. Deleted queries are never exported.

## Spec Change Log

## Verification

- `mvn -f sql-service/pom.xml verify`: BUILD SUCCESS (493 surefire + 91 failsafe, 0 failures, 1 skipped pre-existing `SqlEvalIT`).
- `npx ng test --watch=false --browsers=ChromeHeadless` (Edge): 206 / 206 SUCCESS.
- `npx ng build`: success.
