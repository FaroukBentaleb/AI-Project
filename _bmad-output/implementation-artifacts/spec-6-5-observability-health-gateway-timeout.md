---
title: 'Story 6.5: Observability, health, and gateway timeout'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'b080caa'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-1-qdrant-collection-bootstrap.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-4-rag-grounding-team-examples.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Generation (6.1-6.4) is opaque: no single log line says what a request did, health ignores the LLM, and the gateway's default timeout could cut a valid 13 s generation short (NFR7, addendum §A/§E/§G).

**Approach:** One structured INFO line per generation on the named logger `sql.generation`; an `llm` health indicator in the `dependencies` group that reads cached state only; `response-timeout: 15000` metadata on the gateway's sql-service route. Also give sql-service its own compose model variable `SQL_HF_MODEL`.

## Boundaries & Constraints

**Always:** Log line has no prompt, example, or reply text and never the token (NFR5); health never calls the provider; root `/actuator/health` stays DB-only (dependency indicators excluded, as in 5.1).

**Never:** No API or frontend change, no change to other gateway routes or other services' compose blocks, no real HF call in tests.

## Decisions

- **Log line format:** key=value on logger `sql.generation`, written in a `finally` so success and every failure log exactly once: `generation outcome=<OK|error code> prompt_version prompt_chars examples example_ids rag_warnings warnings rag_ms llm_calls llm_ms=[per call] total_ms`. `outcome` is the envelope `error` code (SQL_REJECTED, SQL_PARSE_ERROR, LLM_REFUSED, LLM_*), so it doubles as the validation outcome. Prompt is logged by length, examples by id only. `rag_ms` covers embedding + Qdrant together (separate timings would need retriever changes; deferred).
- **Logging levels (§G):** `logging.level.root: INFO`; `sql.generation` is the named logger, overridable with `SQL_GENERATION_LOG_LEVEL` (e.g. DEBUG for the demo). No separate demo profile file was added.
- **`llm` indicator, cheap check:** `LlmClient` keeps an `AtomicReference<LastCall(at, cause)>` updated by real generation calls; the indicator reads it plus `hasToken()`. No token → `OUT_OF_SERVICE`; token, no call since startup → `UNKNOWN`; last call answered → `UP`; last call failed → `DOWN` with `last_call` cause and FR-19 `error` code. No caching TTL or probe call needed: health is pure in-memory. `NOT_CONFIGURED` short-circuits are not recorded as calls.
- **Group status:** `UNKNOWN` ranks below `UP` in the default aggregator, so a fresh service with Qdrant up reports `dependencies` UP (200). A failed last call or missing token makes `dependencies` 503, by design; root health is unaffected.
- **Group membership:** `management.endpoint.health.group.dependencies.include: qdrant,llm` (addendum §A); `HealthGroupsConfig` already excludes every group member from the root.
- **Gateway:** the repo uses Spring Cloud Gateway YAML routes, same format as addendum §E, so `metadata.response-timeout: 15000` was added to the `sql-service` route only (22000 under Plan B, noted in a comment). api-gateway has no test sources; validated by `mvn verify` (build) and a live request through the gateway.
- **Compose:** sql-service block uses `HF_MODEL: ${SQL_HF_MODEL:-defog/sqlcoder-7b-2}`; `SQL_HF_MODEL=` added to `.env.example`. Other services keep the shared `HF_MODEL`.
- **13 s through-gateway AC:** covered by config (15 s > 13 s deadline) plus the 6.3 deadline tests; a live 13 s delayed-LLM run through the gateway was not automated (deferred).

</frozen-after-approval>

## Code Map

- `service/GenerationService.java` -- per-request `Trace`, NFR7 line in `finally`, per-call LLM timing.
- `llm/LlmClient.java` -- `LastCall`, `configured()`, `lastCall()`.
- `llm/LlmHealthIndicator.java` (new), `config/LlmConfig.java` (bean `llmHealthIndicator` → `llm`).
- `application.yml` -- group `qdrant,llm`; logging levels.
- `api-gateway/src/main/resources/application.yml`, `docker-compose.yml`, `.env.example`.
- Tests: `llm/LlmHealthIndicatorTest`, `service/GenerationServiceRagTest#logsOneNfr7LineWithoutText`, `controller/GenerationObservabilityIT`.

## Tasks & Acceptance

**Execution:**
- [x] NFR7 log line on `sql.generation`, success and all failure paths.
- [x] `LlmClient` last-call state and `llm` indicator in `dependencies`.
- [x] Logging levels per §G.
- [x] Gateway `response-timeout: 15000` on the sql-service route.
- [x] `SQL_HF_MODEL` in sql-service compose block and `.env.example`.
- [x] Unit + IT tests; `mvn verify` for sql-service and api-gateway; compose smoke check.

**Acceptance Criteria:**
- Given any generation, when it completes or fails, then exactly one line with the NFR7 fields and no prompt/example text is logged.
- Given repeated `/actuator/health` and `/actuator/health/dependencies` polling, then MockWebServer sees zero LLM requests and root health contains only non-dependency indicators (db).
- Given the gateway route timeout of 15 s, a generation bounded by the 13 s deadline returns the sql-service envelope, not a gateway timeout.

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- BUILD SUCCESS, 545 tests green.
- `mvn -f api-gateway/pom.xml verify` -- BUILD SUCCESS (no tests in module).
- `docker compose up -d --build api-gateway sql-service` -- both healthy; `/actuator/health` 200 UP (no components listed); `/actuator/health/dependencies` showed `llm` UNKNOWN then DOWN (PROVIDER_ERROR) after one manual generate; `GET :8080/api/sql/queries` 200 via gateway; `POST :8080/api/sql/generate` returned the sql-service 503 `LLM_UNAVAILABLE` envelope and logged one `sql.generation` line.
