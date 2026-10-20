---
title: 'Story 8.2: Demo hardening — warm-up, burst test, Plan B switch'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'c2e849f'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-8-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-8-1-e2e-uj1-gateway-angular.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-summary.md'
  - '{project-root}/_bmad-output/planning-artifacts/prds/prd-sql-service-2026-10-18/prd.md'
  - '{project-root}/_bmad-output/planning-artifacts/prds/prd-sql-service-2026-10-18/addendum.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The demo depends on a shared, free-tier HF token and possibly slow or cold models. There is no warm-up, no measured quota margin (SM-5), no one-step Plan B (PRD §6/§7 fallback column), no demo-time LLM-down switch (addendum §G 5.5), and the Week-6 go/no-go (OQ-1) is unrecorded.

**Approach:** `scripts/warmup.sh` warms both models and the gateway path; `scripts/burst-test.mjs` runs the SM-5 burst and prints p95 and outcomes by error code; a `planb` Spring profile plus `docker-compose.planb.yml` flips LLM timeout, deadline, gateway route timeout and model together; a `demo` profile carries INFO logging and the dead-host switch. Everything is rehearsed against the 8.1 stub (configurable latency). The runbook `docs/sql-service-demo-runbook.md` records the procedure and the current NO-GO.

## Boundaries & Constraints

- No burst against the real provider in this story; the script refuses a non-stub model unless `BURST_ALLOW_REAL=1`.
- `HF_API_TOKEN` is never printed (warm-up passes it via a temp header file).
- Other teams' services unchanged; the gateway edit touches the sql-service route only, default stays 15000 ms.
- No frontend change.

</frozen-after-approval>

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| Default config | no profile | `hf.timeout` 5 s, deadline 13 s, gateway 15000 ms |
| Plan B | `docker-compose.planb.yml` | profiles `demo,planb`: 8 s / 20 s, gateway 22000 ms, model `SQL_HF_MODEL_PLANB` or `SQL_HF_MODEL` |
| Env override | `HF_TIMEOUT` set | env wins over the profile (documented: leave unset) |
| Dead host | `SQL_DEMO_LLM_DEAD_HOST=true` (demo profile) | 503 `LLM_UNAVAILABLE` at once, token still configured; embeddings untouched |
| Warm-up, model loading | HF 503 / 504 / network | wait `WARMUP_WAIT_S`, retry up to `WARMUP_ATTEMPTS` |
| Warm-up, 401/403/404/429 | — | fail at once with the meaning |
| Burst | 15 req, conc. 5 | p50/p95/max, outcomes by code; exit 0 GO only if 0 failures, 0 quota, p95 <= 10 s (15 s with `BURST_PLAN=b`) |
| Burst, real model | model not `stub/…` | refused (exit 2) without `BURST_ALLOW_REAL=1` |

## Decisions

- **Plan B mechanism: a compose override file (`docker-compose.planb.yml`) is the single switch.** It sets sql-service `SPRING_PROFILES_ACTIVE=demo,planb` (the `planb` profile in `application-planb.yml` holds 8 s / 20 s, as the story asks) and api-gateway `SQL_ROUTE_RESPONSE_TIMEOUT_MS=22000`. A profile alone cannot reach the gateway; one file flips all three and is undone by a plain `docker compose up -d`.
- **Gateway timeout parametrised** as `response-timeout: ${SQL_ROUTE_RESPONSE_TIMEOUT_MS:15000}` (SCG parses string metadata). The story only required the 22 s to be documented; making it switchable avoids a hand edit on demo day.
- **Plan B model:** `HF_MODEL: ${SQL_HF_MODEL_PLANB:-${SQL_HF_MODEL:-defog/sqlcoder-7b-2}}`. No Plan B model is chosen yet (OQ-1); blank keeps the current model.
- **Demo profile is the compose default** (`SQL_SPRING_PROFILES`, default `demo`). It only restates INFO logging and adds `sql.demo.llm-dead-host` (default false), so the runtime behaviour is unchanged.
- **Dead host = `http://127.0.0.1:9`** applied in `LlmConfig` over `hf.base-url`, generation only: connection refused at once maps to `UNREACHABLE` -> `LLM_UNAVAILABLE`; a property (not a URL env var) so it also wins over `HF_BASE_URL` set by the e2e override.
- **Warm-up calls HF directly** (base URL overridable for the stub, stub port 8099 published by the e2e override), then checks the library count and one generate through the gateway.
- **Burst concurrency 5 by default** (`BURST_CONCURRENCY`), outcome = FR-19 `error` code, `GATEWAY_TIMEOUT` for 504, `NETWORK_*` otherwise.
- **Stub latency** `STUB_LATENCY_MS` / `STUB_JITTER_MS` on generation calls only.
- **Go/no-go recorded as NO-GO** (2026-10-20) pending the model decision from `epic-6-summary.md`.

## Code Map

- `sql-service/src/main/resources/application-planb.yml`, `application-demo.yml`; `config/LlmConfig.java` (dead host).
- `sql-service/src/test/.../config/DemoProfilesTest.java` -- binding of default / planb / demo+planb / demo; dead host -> `LLM_UNAVAILABLE`.
- `api-gateway/src/main/resources/application.yml` -- sql route timeout placeholder.
- `docker-compose.yml` (sql-service profiles + dead-host env), `docker-compose.planb.yml`, `docker-compose.e2e.yml` (stub latency, port 8099), `.env.example`.
- `scripts/warmup.sh`, `scripts/burst-test.mjs`, `scripts/e2e/llm-stub/stub.py`.
- `docs/sql-service-demo-runbook.md`; `docs/sql-service-e2e.md` (restore command, link).

## Tasks & Acceptance

- [x] Write this spec.
- [x] `planb` and `demo` profiles, dead-host switch, unit tests.
- [x] Gateway route timeout switchable; `docker-compose.planb.yml`.
- [x] Stub latency; `scripts/warmup.sh`; `scripts/burst-test.mjs`.
- [x] Runbook with go/no-go (NO-GO recorded) and real-provider commands.
- [x] `mvn verify` (sql-service, api-gateway) green; warm-up and burst (normal and Plan B) on the stub stack; stack restored to the real config.

**AC:** see `epics-sql-service.md` Story 8.2. The real-provider warm-up and SM-5 burst remain to run once a model is chosen.

## Spec Change Log

## Verification

- `mvn -f sql-service/pom.xml verify`: BUILD SUCCESS, 475 surefire + 85 failsafe, 0 failures, 1 skipped (`SqlEvalIT`).
- `mvn -f api-gateway/pom.xml verify`: success.
- Stub warm-up: generation 1413 ms, embedding 7 ms, library 17, gateway generate 1545 ms.
- Burst normal, stub 1500±500 ms: 15/15 OK, p95 2039 ms, GO.
- Burst normal, stub 6500±500 ms: 5/5 `LLM_UNAVAILABLE` (5 s timeout), NO-GO as expected.
- Burst Plan B, stub 6500±500 ms: 15/15 OK, p95 7054 ms (target 15 s), GO; gateway env 22000, profiles `demo,planb`.
- Dead host on the stack: 503 `LLM_UNAVAILABLE` in 116 ms.
- Restored: sql-service profile `demo`, `HF_MODEL` `defog/sqlcoder-7b-2`, no `HF_BASE_URL`, gateway default 15000, llm-stub stopped.
