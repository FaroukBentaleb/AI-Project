# sql-service demo runbook and go/no-go

Story 8.2 (PRD §6 Plan B, §7, SM-5, OQ-1; addendum §G). Scripts: `scripts/warmup.sh`, `scripts/burst-test.mjs`,
`scripts/e2e/uj1-e2e.mjs`. Overrides: `docker-compose.planb.yml` (Plan B), `docker-compose.e2e.yml` (stub LLM).

## Go/no-go (OQ-1, Week 6)

**Current decision (2026-10-20): NO-GO, pending a model decision.** `defog/sqlcoder-7b-2` is not served by HF
Inference Providers (`epic-6-summary.md`): live generation returns 503 `LLM_UNAVAILABLE`. The options are in
`epic-6-summary.md` (dedicated endpoint, a served model + chat-completions client and `v2` prompt, or self-hosting).
Choosing one is the module owner's decision; until then the demo runs on nothing but the stub rehearsal below.

GO requires, on the real provider, in this order:

1. `./scripts/warmup.sh` exits 0 (both models 200, library non-empty, one gateway generate 200).
2. `E2E_MODE=real node scripts/e2e/uj1-e2e.mjs` passes.
3. SM-5 burst, 24 h (and at least 3 days) before the demo, with **every team service up and in use on the shared
   token**: `BURST_ALLOW_REAL=1 node scripts/burst-test.mjs` reports `SM-5 GO` (0 failures, 0 `LLM_QUOTA_EXCEEDED`,
   p95 <= 10 s).
4. If 3 fails on latency (or `LLM_UNAVAILABLE` timeouts) only: switch to Plan B and rerun with
   `BURST_PLAN=b BURST_ALLOW_REAL=1 node scripts/burst-test.mjs` (p95 <= 15 s). GO on Plan B if it passes.
5. Quota failures (`LLM_QUOTA_EXCEEDED`) are NO-GO on either plan: they are not fixed by timeouts.

Record each run (date, plan, p95, outcomes) in the table at the end. Run the real burst **once**: it spends the
team's shared free credits. The burst script refuses a non-stub model without `BURST_ALLOW_REAL=1`.

## Plan B switch

One switch, `docker-compose.planb.yml`, flips everything together:

| Setting | Normal | Plan B | Where |
|---|---|---|---|
| `hf.timeout` (LLM call, x at most 2) | 5 s | 8 s | sql-service profile `planb` (`application-planb.yml`) |
| `sql.generation.deadline` | 13 s | 20 s | same profile |
| Gateway `/api/sql/**` response timeout | 15000 ms | 22000 ms | `SQL_ROUTE_RESPONSE_TIMEOUT_MS` (api-gateway `application.yml`) |
| Generation model | `SQL_HF_MODEL` | `SQL_HF_MODEL_PLANB`, else `SQL_HF_MODEL` | `HF_MODEL` of sql-service |
| p95 target (burst) | 10 s | 15 s | `BURST_PLAN=b` |

```bash
docker compose -f docker-compose.yml -f docker-compose.planb.yml up -d sql-service api-gateway   # Plan B on
docker compose up -d sql-service api-gateway                                                      # Plan B off
```

Check: `docker compose logs sql-service | grep "profiles are active"` shows `"demo", "planb"`, and
`docker compose exec api-gateway printenv SQL_ROUTE_RESPONSE_TIMEOUT_MS` prints 22000. An explicit `HF_TIMEOUT` or
`SQL_GENERATION_DEADLINE` env var would override the profile: leave them unset.

## Demo profile

sql-service runs with `SPRING_PROFILES_ACTIVE=${SQL_SPRING_PROFILES:-demo}`: INFO logging, and only the named
`sql.generation` logger can go to DEBUG (`SQL_GENERATION_LOG_LEVEL=DEBUG`).

**LLM-down scenario (§G 5.5):** `SQL_DEMO_LLM_DEAD_HOST=true docker compose up -d sql-service` points generation
(not embeddings) at a dead host (`http://127.0.0.1:9`). `/generate` returns 503 `LLM_UNAVAILABLE` with
"SQL generation temporarily unavailable. You can save a query manually." (not `LLM_NOT_CONFIGURED`); manual save
still indexes. Back: `docker compose up -d sql-service`.

## Timeline

1. **Week 6:** go/no-go above; Plan B if needed.
2. **3+ days before:** UJ-1 E2E through gateway and Angular (`docs/sql-service-e2e.md`), SM-5 burst, a run on the
   demo network.
3. **Demo morning:** `./scripts/warmup.sh` (reads `.env`; the token is sent by header file, never printed). If the
   library is empty or examples are missing: `curl -X POST -H 'X-User-Id: demo' localhost:8080/api/sql/admin/reindex`.
   `HF_EMBEDDING_MODEL` must be set in `.env` (empty = RAG and indexing off).
4. **Scenarios:** simple select, join, relative date, FR-11 attacks on Validate, LLM-down (above), RAG lift (8.3).

## Rehearsal against the stub (no HF calls)

```bash
STUB_LATENCY_MS=1500 STUB_JITTER_MS=500 docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build llm-stub sql-service api-gateway
WARMUP_BASE_URL=http://localhost:8099 HF_API_TOKEN=stub SQL_HF_MODEL=stub/sqlcoder HF_EMBEDDING_MODEL=stub/embedder ./scripts/warmup.sh
node scripts/burst-test.mjs
# Plan B: a stub latency between 5 s and 8 s fails normal mode (LLM_UNAVAILABLE timeouts) and passes Plan B
export STUB_LATENCY_MS=6500 STUB_JITTER_MS=500
docker compose -f docker-compose.yml -f docker-compose.planb.yml -f docker-compose.e2e.yml up -d llm-stub sql-service api-gateway
BURST_PLAN=b node scripts/burst-test.mjs
# back to the real config
docker compose up -d sql-service api-gateway && docker compose -f docker-compose.yml -f docker-compose.e2e.yml stop llm-stub
```

## Run record

| Date | Target | Plan | n / conc. | p95 | Outcomes | Verdict |
|---|---|---|---|---|---|---|
| 2026-10-20 | stub, 1500±500 ms | normal | 15 / 5 | 2039 ms | OK=15 | GO (rehearsal) |
| 2026-10-20 | stub, 6500±500 ms | normal | 5 / 5 | 5128 ms | LLM_UNAVAILABLE=5 (5 s timeout) | NO-GO, as expected |
| 2026-10-20 | stub, 6500±500 ms | Plan B | 15 / 5 | 7054 ms | OK=15 | GO (rehearsal) |
| 2026-10-20 | real provider | — | — | — | not run | NO-GO: model decision pending |
