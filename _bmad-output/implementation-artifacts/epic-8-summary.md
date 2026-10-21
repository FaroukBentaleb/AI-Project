# Epic 8 summary: Demo readiness, end-to-end proof and Could features

Epic 8 proves the SQL module end to end, hardens it for the demo, and ships the two Could features (FR34 similar queries at save, FR35 export). Code lives in `sql-service/`, `frontend/src/app/features/sql/`, `scripts/` and `docs/`; one gateway config change (route timeout, 8.2).

Final verification (Story 8.5 run, 2026-10-20): `mvn -f sql-service/pom.xml verify` BUILD SUCCESS (493 surefire + 91 failsafe, 0 failures, 1 skipped pre-existing `SqlEvalIT`); `npx ng test --watch=false --browsers=ChromeHeadless` with headless Edge 206 / 206 SUCCESS; `npx ng build` succeeded.

## Delivered

- **8.1 End-to-end UJ-1:** `scripts/e2e/uj1-e2e.mjs` drives generate -> save -> search -> copy/usage -> validate (plus RAG re-ask, rejected path, LLM-down manual save) through the gateway against a deterministic LLM stub (`docker-compose.e2e.yml`); 10/10 steps. `SQL_REJECTED` now carries `details.safety_note`, rendered on the Generate error state. Results in `docs/sql-service-e2e.md`.
- **8.2 Demo hardening:** `scripts/warmup.sh`, `scripts/burst-test.mjs` (SM-5 p95 and outcomes by code), `planb` profile + `docker-compose.planb.yml`, `demo` profile; runbook `docs/sql-service-demo-runbook.md` records the procedure and the current NO-GO on the real provider.
- **8.3 Scripted RAG lift (UJ-3):** `scripts/e2e/uj3-rag-lift.mjs` + fixture; 3/3 stub runs.
- **8.4 Similar queries at save (FR34):** `POST /api/sql/queries/similar` reusing `ExampleRetriever`; up to 3 links on the Save form; always advisory (`[]` on any failure).
- **8.5 Export as .sql (FR35):** `GET /api/sql/queries/export` with the list's filters and sort, all pages, deleted never included; `text/plain` attachment `sql-library-YYYY-MM-DD.sql`. Each query: `-- Label: value` header lines (title, description, author, tags, read-only, deprecated note) with line breaks collapsed and `/*` `*/` neutralised, then the SQL terminated by a `;` that a trailing `--` comment cannot swallow. "Export .sql" button on the Library search row with Exporting state and a dismissible error banner (UI designed minimally, recorded in the spec).

## Deferred / risks

- Live generation still depends on the real-provider go/no-go (NO-GO recorded in 8.2); 8.1/8.3 were proven against the stub only.
- RAG threshold (OQ-7) stays 0.5; tuning needs the real models (8.3).
- Export: no row cap (team-scale library assumed); notes and NL prompt are not exported; if a saved SQL ended inside an unterminated block comment the appended `;` would be commented out (the validator rejects unparseable SQL, so this should not occur). Behind a cross-origin gateway that does not expose `Content-Disposition`, the browser falls back to `sql-library.sql`.
- Export UI and similar-queries panel are not in EXPERIENCE.md/DESIGN.md; both were added minimally in the existing visual language and should be reviewed by UX.
