---
title: 'Story 8.1: End-to-end UJ-1 check through gateway and Angular'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'b080caa'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-8-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-summary.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-summary.md'
  - '{project-root}/_bmad-output/planning-artifacts/prds/prd-sql-service-2026-10-18/prd.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-sql-service-2026-10-18/EXPERIENCE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Every layer is unit/integration tested in isolation, but nobody has proven the full UJ-1 path (browser -> gateway -> sql-service -> Qdrant/HF) on `docker compose up`. Live generation is blocked by model availability, so a check that depends on HF cannot be the default. Also (7.3 gap) a rejected generation shows no Safety Note because the 422 envelope does not carry it.

**Approach:** A dependency-free Node script drives UJ-1 through the gateway (generate -> save -> search -> copy/usage -> validate, plus the RAG re-ask, the rejected path and the LLM-down manual-save path). A compose override swaps sql-service's HF endpoints for a deterministic stub container. A UI checklist covers the Angular flow. Results go to `docs/sql-service-e2e.md`. sql-service adds `details.safety_note` on `SQL_REJECTED`; the Generate error state renders it verbatim.

## Boundaries & Constraints

- Other teams' services and the base `docker-compose.yml` are unchanged; the override reconfigures sql-service only.
- The real `HF_API_TOKEN` is never sent to the stub and never printed.
- The UI keeps holding no copy of the Safety Note.

</frozen-after-approval>

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| UJ-1 happy path (stub) | "orders per region last week vs the week before" | 200 SELECT on `orders`, `safety_note`, `examples_used` >= 1 (seed) |
| Save generated | SQL + original prompt, `is_llm_generated` | 201, author = X-User-Id, `read_only`, `indexed` true |
| Search / copy / validate | `q=<run id>`; POST usage; POST validate | hit; `use_count` 1; accepted + Safety Note |
| RAG re-ask | same prompt | saved id in `examples_used` |
| Rejected | prompt containing "delete" | 422 `SQL_REJECTED`, `rejected_sql`, reasons, `details.safety_note` |
| LLM down | prompt containing "outage" (stub HF 503) | 503 `LLM_UNAVAILABLE` + PRD copy, `retry_after_seconds` 20; manual save 201 `is_llm_generated` false |
| `LLM_REFUSED` / `SQL_PARSE_ERROR` | — | `details.safety_note` null; UI shows no Safety Note |
| Real provider | `E2E_MODE=real` | happy-path steps only, stub-specific asserts skipped |

## Decisions

- **E2E tool: API-level Node script + manual UI checklist + Angular component tests**, not Playwright/Cypress. The story asks for an API script and a UI checklist; Playwright/Cypress would add a large dev dependency and browser downloads (no Chrome installed; Edge works for Karma). The Generate/Save UI states are already covered by Karma specs against mocked HTTP; the script adds a frontend-served check and a CORS preflight from the 4200 origin.
- **Stub LLM:** `python:3.12-alpine` running a stdlib-only `scripts/e2e/llm-stub/stub.py` (HF text-generation + feature-extraction shapes, chunked request bodies). Replies are keyed on the `[QUESTION]` text. Embeddings are a hashed word set with the first line weighted 4x so the seed "Orders per region" query scores above the 0.5 threshold.
- **Isolation:** the override uses Qdrant collection `sql_embeddings_e2e` (via `QDRANT_COLLECTION`) and a dummy token; the script soft-deletes the rows it creates. Postgres `indexed` flags are realigned by reconciliation when sql-service restarts on the real config.
- **Safety Note on errors: `SQL_REJECTED` only.** EXPERIENCE.md lists the Safety Note for `SQL_REJECTED` ("shows with every SQL result, accepted or rejected") but not for `LLM_REFUSED` / `SQL_PARSE_ERROR` (no SQL to qualify). `GenerationErrorDetails` gains `safety_note` (always present, null elsewhere).
- **Run on the stub by default**; the real-provider run is documented and gated on the 8.2 go/no-go model decision.

## Code Map

- `sql-service/.../dto/GenerationErrorDetails.java`, `service/GenerationService.java`, `exception/LlmFailureException.java` -- `safety_note`.
- `sql-service/.../controller/GenerateControllerTest.java` -- asserts it on `SQL_REJECTED`, null on parse error.
- `frontend/.../generate/generate-error.ts`, `generate.component.ts`, `generate-errors.spec.ts` -- `safetyNote`, render, tests.
- `docker-compose.e2e.yml`, `scripts/e2e/llm-stub/stub.py`, `scripts/e2e/uj1-e2e.mjs`, `docs/sql-service-e2e.md`.

## Tasks & Acceptance

- [x] Write `epic-8-context.md` and this spec.
- [x] `safety_note` in 422 `SQL_REJECTED` details, with tests.
- [x] Render it in the Generate error state, with tests (present / absent / not on refused or parse error).
- [x] Stub LLM container + compose override.
- [x] API-level E2E script through the gateway; UI checklist and run record in `docs/sql-service-e2e.md`.
- [x] `mvn verify`, `ng test`, `ng build` green; E2E run against the compose stack (stub) passes.

**AC:** Given the full stack on docker compose, when the E2E script runs through the gateway, every step succeeds; the run is recorded with a date (NFR8). The UJ-1 UI checklist is to be ticked by a human on the demo machine.

## Spec Change Log

## Verification

- `mvn -f sql-service/pom.xml verify`: BUILD SUCCESS, 554 tests, 0 failures, 0 errors, 1 skipped.
- `npx ng test --watch=false --browsers=ChromeHeadless` (CHROME_BIN = Edge): 196 / 196 SUCCESS.
- `npx ng build`: success.
- `node scripts/e2e/uj1-e2e.mjs` (stub, compose stack): 10 / 10 steps passed, 2026-10-20.
