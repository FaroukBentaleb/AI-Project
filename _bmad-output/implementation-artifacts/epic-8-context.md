# Epic 8 Context: Demo Readiness and Could-Haves

<!-- Compiled from planning artifacts (epics-sql-service.md Epic 8, PRD UJ-1/UJ-3, EXPERIENCE.md), epic-2..7 summaries. Edit freely. -->

## Goal

Make the live demo reliable: an end-to-end UJ-1 check, a shared-token burst test, warm-up, the Week-6 go/no-go and Plan B switch, and a scripted UJ-3 RAG-lift test. Then the optional features (FR34 similar queries at save, FR35 export) if time allows.

## Stories

- 8.1: End-to-end UJ-1 check through gateway and Angular (API script + UI checklist, results in `docs/sql-service-e2e.md`, NFR8)
- 8.2: Demo hardening: `scripts/warmup.sh`, 15-request burst test (SM-5), `planb` profile (8 s LLM / 20 s deadline / 22 s gateway), dead-host demo property, runbook + go/no-go (OQ-1)
- 8.3: Scripted RAG-lift test (UJ-3) against the real models; tune `sql.rag.threshold` (OQ-7)
- 8.4: Similar queries at save time (FR34, Could)
- 8.5+: Export (FR35, Could)

## Requirements & Constraints

- Full stack is `docker compose up` (eureka, postgres, qdrant, api-gateway :8080, five services, frontend :4200 via nginx). The browser calls the gateway directly (`environment.apiBaseUrl = http://localhost:8080/api`), CORS from `FRONTEND_ORIGIN`.
- Gateway route `/api/sql/**` -> `lb://sql-service`, response timeout 15 s (22 s under Plan B). Generation deadline 13 s.
- sql-service HF config is env-driven: `HF_API_TOKEN`, `HF_MODEL` (compose: `SQL_HF_MODEL`, default `defog/sqlcoder-7b-2`), `HF_BASE_URL`, `HF_EMBEDDING_MODEL`, `HF_EMBEDDING_BASE_URL`, `HF_EMBEDDING_DIMENSION` (384); Qdrant collection `qdrant.collection` (`sql_embeddings`).
- UJ-1: generate -> SQL + Warnings + Safety Note + Examples used -> one-click copy -> save (prefill: SQL + original prompt) -> indexed before confirmation. Edge case: LLM down -> "SQL generation temporarily unavailable. You can save a query manually." -> manual save.
- The Safety Note is server-owned and shown with every SQL result, accepted or rejected (EXPERIENCE.md); on `LLM_REFUSED` / `SQL_PARSE_ERROR` there is no SQL and no Safety Note.
- Never print `HF_API_TOKEN`. Other teams' services must not be changed.

## Known Blockers

- Live generation with `defog/sqlcoder-7b-2` via HF Inference Providers fails (model likely not served, `epic-6-summary.md`). E2E must run deterministically against a stub LLM; the real-provider run is documented and gated on the model decision (8.2 go/no-go).

## Dependencies

- 8.1 needs 7.3, 4.5. 8.2 needs 8.1. 8.3 needs 8.2, 6.6.
