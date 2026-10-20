---
title: 'Story 8.4: Similar queries at save time (Could)'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'c2e849f'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-8-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-4-rag-grounding-team-examples.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-3-index-on-save-keep-in-sync.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-4-save-query-manually.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-2-save-generated-sql-short-form.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/DESIGN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Developers save near-duplicates because nothing tells them a similar query already exists.

**Approach:** FR34. `POST /api/sql/queries/similar` (body: `title`, `description`, `sql_content`) embeds the draft the same way saved queries are embedded, searches `sql_embeddings` with the generation threshold, re-checks eligibility in PostgreSQL, and returns up to 3 Indexed Queries `{id, title}`. The Save form (create mode) shows them as links before submit.

## Boundaries & Constraints

- Reuse `ExampleRetriever` (embedding, Qdrant search, `QueryIndexer.shouldBeIndexed` re-check); no second retrieval path.
- Never blocks or slows Save: the request is independent of submit; RAG off, embedding/Qdrant/DB failure → `200 []` and no UI.
- Prompt/draft text is never logged. Edit mode is out of scope.

</frozen-after-approval>

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| Similar draft | Draft close to ≥ 3 Indexed Queries | 200, 3 items `{id,title}`, best first; form shows 3 links |
| Stale point | Hit is deprecated/deleted/non-read-only | Dropped by the Postgres re-check |
| Nothing above threshold | Unrelated draft | `[]`; no panel |
| Qdrant / embedding down | Any failure | `[]`; no panel, no error; Save works |
| Blank draft | All fields blank | `[]`, no embedding call; client sends nothing |
| Oversized fields | Title > 200 etc. | Truncated to create limits, never 400 |
| Typing | Several keystrokes | One request after 600 ms quiet; identical draft not re-sent |

## Decisions

- **Retrieval reuse:** new `ExampleRetriever.similar(text, limit)` shares `qualifying()`; searches `max(top-k, limit)` hits so the eligibility filter can still fill 3. Same `sql.rag.threshold` and `sql.rag.enabled` switch.
- **Draft text:** `QueryIndexer.embeddedText(title, description, sql, null)` overload, identical layout to stored points so vectors are comparable.
- **Endpoint:** separate `SimilarQueryController` (keeps `SavedQueryController` slice tests unchanged); always 200 with a bare JSON array; no validation, fields truncated (advisory endpoint must never error the form).
- **UX (not covered by EXPERIENCE.md/DESIGN.md — designed minimally):** a bordered aside "Similar queries already in the library" below the SQL field, up to 3 title links in the accent colour; hidden when empty; inside an `aria-live="polite"` slot. Links open in a new tab (`target="_blank"`) so the unsaved draft and the discard guard are untouched.
- **Trigger:** debounced (600 ms) on Title/Description/SQL input and once on open when prefilled (Validate/Generate); `switchMap` cancels stale requests; errors map to `[]`; client caps to 3.
- **Existing specs:** their `afterEach` drops `/queries/similar` requests before `verify()`; no behaviour change.

## Code Map

- `sql-service/.../rag/ExampleRetriever.java` -- `similar()`.
- `sql-service/.../rag/QueryIndexer.java` -- `embeddedText` string overload.
- `sql-service/.../controller/SimilarQueryController.java`, `dto/SimilarQueriesRequest.java`, `dto/SimilarQueryRef.java` -- endpoint.
- `frontend/.../core/models.ts`, `core/sql-api.service.ts` -- `SimilarQuery`, `similarQueries()`.
- `frontend/.../save/save-query.component.ts|.css` -- suggestions panel and debounced fetch.

## Tasks & Acceptance

- [x] `ExampleRetriever.similar` + draft embedded-text overload; unit tests (limit after filter, disabled/blank, embedding/Qdrant failure, no hits).
- [x] `POST /api/sql/queries/similar`; WebMvc test (shape, limit 3, blank, truncation) and IT with Qdrant Testcontainer + stub embedding (3 of 4 live, deprecated excluded, nothing above threshold, embedding down → `[]` and save works).
- [x] Save form suggestions panel; frontend tests (debounce + body, 3-link cap with new-tab href, empty, failure → no UI and Save works, Save not waiting, blank draft).

**AC:** Given a draft similar to a seed query, when I open the save form, then up to 3 similar queries are shown as links. Given Qdrant is down, when I open the save form, then no suggestions are shown and saving still works.

## Spec Change Log

## Verification

- `mvn -f sql-service/pom.xml verify`: BUILD SUCCESS (571 tests, 0 failures, 1 skipped pre-existing).
- `npx ng test --watch=false --browsers=ChromeHeadless` (Edge): 201 / 201 SUCCESS.
- `npx ng build`: success.
