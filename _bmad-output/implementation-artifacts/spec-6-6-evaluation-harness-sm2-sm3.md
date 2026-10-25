---
title: 'Story 6.6: Evaluation harness for SM-2 and SM-3'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'b080caa'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-5-seed-corpus-on-startup.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-4-rag-grounding-team-examples.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The technical report needs numbers for SM-2 (executable rate) and SM-3 (RAG lift), measured the same way every time (addendum §H: 15 held-out prompts x {RAG on, RAG off} x 3 runs, each output executed read-only against `seed/schema.sql` in a throwaway PostgreSQL).

**Approach:** A test-scope harness (`com.devassisthub.sql.eval`) that calls running sql-service instances over HTTP, executes every returned SQL in a sandboxed throwaway Testcontainers PostgreSQL, and writes `target/eval-report.md`. The real run is opt-in via the Maven `eval` profile; an offline self-test with a stubbed generator runs in normal `mvn verify`.

## Boundaries & Constraints

**Always:** Generated SQL runs only in the harness container: role `eval_ro` (SELECT grants only, `default_transaction_read_only`), READ ONLY transaction, `statement_timeout`, always rolled back. Held-out prompts audited against the seed corpus (SM-C2).

**Never:** Never executed on sql_db or the IT database; no real HF call in `mvn verify`/CI; no production code, API, or frontend change; the harness never reads or prints `HF_API_TOKEN` (it stays in sql-service).

## Decisions

- **Gating:** the real run is `SqlEvalIT`, `@EnabledIfSystemProperty(eval.run=true)`. Only the `eval` Maven profile sets it (and skips surefire, restricts failsafe to `SqlEvalIT`). Default `mvn verify` reports it as 1 skipped test. Chosen over a JUnit tag or main class: no surefire/failsafe changes for the default build, and Testcontainers lifecycle comes for free.
- **Black-box over HTTP:** the harness calls `POST /api/sql/generate` on a running service (`eval.rag-on.url`, `eval.rag-off.url`), so it measures the full pipeline (prompt template, extraction, validator, corrective retry) with whatever model the service is configured with. RAG is a server setting (`SQL_RAG_ENABLED`), not a request field, so RAG on/off are two base URLs (two instances, or one instance restarted between runs). No API change was made to toggle RAG per request.
- **Scoring:** attempt = prompt x config x run. Executable = service returned 200 with `sql` AND it executed in the sandbox. Any generation error (422/503/transport) counts as not executable and is listed with its envelope code. Rate = executable / attempts per config; lift = rate(on) - rate(off) in percentage points.
- **Sandbox hardening:** besides the read-only transaction, the dedicated low-privilege role means a multi-statement escape (`COMMIT; DELETE ...`, `SET TRANSACTION READ WRITE; ...`) still cannot write; verified by the self-test. `setMaxRows(1000)`, statement_timeout default 5 s (`eval.statement-timeout-ms`).
- **Prompt set:** `src/test/resources/eval/prompts.json`, 15 prompts on the seed schema with a `held_out_audit` note. `EvalPromptsTest` enforces 15 unique prompts, no seed title contained, and keyword overlap with each seed title < 75% (a 60% bar flagged "most expensive product per category" vs "Sales by product category", a different intent).
- **Report:** markdown with configuration table, RAG lift, per-prompt Y/N marks per run, and a failure list (envelope code or SQLSTATE + first message line). Run order is run -> config -> prompt, so drift during a run affects both configs evenly.

</frozen-after-approval>

## Code Map

- `src/test/java/com/devassisthub/sql/eval/`: `EvalPrompts`, `SqlGenerator` (+ `Generation`), `HttpSqlGenerator`, `EvalDatabase` (container, role, sandbox), `EvalHarness` (+ `Report`), `SqlEvalIT` (gated real run), `EvalHarnessSelfIT`, `EvalPromptsTest`.
- `src/test/resources/eval/prompts.json`.
- `pom.xml`: `eval` profile.

## Tasks & Acceptance

**Execution:**
- [x] Held-out prompt file with audit note, and audit test.
- [x] Sandbox executor on a throwaway PostgreSQL loaded with `seed/schema.sql`.
- [x] Harness scoring + markdown report to `target/eval-report.md`.
- [x] HTTP generator against running sql-service instances.
- [x] `eval` profile; `SqlEvalIT` skipped in default `mvn verify`.
- [x] Offline self-test (stub generator): scoring, write blocking, timeout, report content.

**Acceptance Criteria:**
- Given valid HF credentials, a working model and the `eval` profile, when the harness runs, then `target/eval-report.md` has per-prompt results, the executable rate for RAG on and off over 3 runs, and the difference in percentage points.
- Given normal `mvn verify`, then `SqlEvalIT` is skipped and no HF call is made.
- The prompt file documents its held-out audit and no prompt paraphrases a seed query (SM-C2).

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- BUILD SUCCESS: surefire 469, failsafe 85 (1 skipped: `SqlEvalIT`), 0 failures. `EvalHarnessSelfIT` 6/6, `EvalPromptsTest` 2/2.
- Profile wiring dry run: `mvn -f sql-service/pom.xml -Peval verify -Deval.rag-on.url=http://localhost:1 -Deval.runs=1` -- SqlEvalIT ran, report written with 0/15 per config (transport failures), no HF call.

**Running the real evaluation (once a working model is configured):**
1. Start two sql-service instances with the same DB, Qdrant and `HF_API_TOKEN`/`HF_MODEL` (seed corpus indexed): RAG on, e.g. `SERVER_PORT=8084`; RAG off, `SERVER_PORT=8094 SQL_RAG_ENABLED=false SQL_SEED_ENABLED=false`. (Or one instance, restarted between two runs with the URLs swapped.)
2. `mvn -f sql-service/pom.xml -Peval verify -Deval.rag-on.url=http://localhost:8084 -Deval.rag-off.url=http://localhost:8094` (optional `-Deval.runs=3 -Deval.statement-timeout-ms=5000 -Deval.report=...`). Docker is required for the sandbox container.
3. Read `sql-service/target/eval-report.md`: "Executable rate" table (RAG on/off: executable, attempts = 45, rate %), a bold "RAG lift: +X.X percentage points" line, a per-prompt table (`P01 ... | YYN | NNN`), and a failure list such as `P07 RAG off run 2: execution 42703 ERROR: column "revenue" does not exist` or `generation SQL_PARSE_ERROR`.
