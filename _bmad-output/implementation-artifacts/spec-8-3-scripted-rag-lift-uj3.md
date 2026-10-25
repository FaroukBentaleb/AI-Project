---
title: 'Story 8.3: Scripted RAG-lift test (UJ-3)'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'c2e849f'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-8-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-8-1-e2e-uj1-gateway-angular.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-8-2-demo-hardening-warmup-burst-planb.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-4-rag-grounding-team-examples.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-6-evaluation-harness-sm2-sm3.md'
  - '{project-root}/_bmad-output/planning-artifacts/prds/prd-sql-service-2026-10-18/prd.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** UJ-3 ("save, then ask again") is the demo's RAG moment and SM-3's visible proof, but nothing checks that it shows reliably: an off-domain prompt must get the no-similar Warning, and after saving the corrected SQL the follow-up must list it under Examples used and reuse its table names. The similarity threshold (OQ-7) is an untuned 0.5.

**Approach:** A fixture pair plus a corrected SQL (`scripts/e2e/uj3-fixture.json`) and a gateway-level script (`scripts/e2e/uj3-rag-lift.mjs`) that runs the sequence N times (default 3), cleaning up after each run. Deterministic against the 8.1 stub, which now answers from the first `<example>` when examples are present and guesses a name otherwise. The real-provider run and threshold-tuning loop are documented, gated on the 8.2 go/no-go.

## Boundaries & Constraints

- No burst against the real provider: 2 generations per run, 2 s pause in real mode.
- `HF_API_TOKEN` never read or printed by the script; no production Java, API or frontend change.
- Stub change must keep UJ-1 (8.1) green.

</frozen-after-approval>

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| First prompt | "late shipments per carrier this month", no similar indexed query | 200, Warning contains "no similar queries found", `examples_used` empty, no `wh_*` table |
| Save | fixture title/description/SQL | 201, `indexed=true` |
| Follow-up | "which carriers had the most late shipments this month" | 200, saved id in `examples_used`, SQL contains `wh_shipments` and `wh_carriers` |
| Next run | saved query soft-deleted | first prompt again shows no-similar (proves cleanup + repeatability) |
| Stub, examples present | `<example>` blocks in prompt | first example's SQL returned |
| Stub, no examples | schema-none template | `SELECT * FROM <word>_data LIMIT 100` |
| Stub, no examples block and no template marker | legacy | `ORDERS_SQL` (unchanged) |

## Decisions

- **Node script, not the 6.6 Java harness.** The harness measures executable rate over 15 held-out prompts with a sandbox DB; UJ-3 is a stateful save-then-ask flow through the gateway. It reuses the 8.1 script conventions (call/check, `E2E_MODE`, `GATEWAY_URL`, wait-for-gateway) instead. "Profile-gated" = `E2E_MODE=real` opt-in; default is stub.
- **Off-domain fixture with `wh_` table names** so an ungrounded model cannot guess them and the seed corpus (customers/products/orders) cannot match.
- **Stub grounding rule:** echo the first (best-scored) example's SQL minus its `-- intent:` line. UJ-1 step 1 still gets an `orders` seed query.
- **Repeatability by cleanup:** each run soft-deletes its saved row, so runs 2..N re-check the no-similar Warning.
- **Threshold not changed (deferred).** The stub's hashed-word embedding says nothing about real-model scores; tuning OQ-7 needs the real models. `sql.rag.threshold` stays 0.5 until the documented real run passes 3/3 three times.

## Code Map

- `scripts/e2e/uj3-fixture.json`, `scripts/e2e/uj3-rag-lift.mjs` -- new.
- `scripts/e2e/llm-stub/stub.py` -- example-aware replies.
- `docs/sql-service-e2e.md` -- UJ-3 section: stub run, real run, tuning loop, run record.

## Tasks & Acceptance

- [x] Write this spec.
- [x] Fixture pair + corrected SQL.
- [x] Stub: grounded vs ungrounded replies.
- [x] UJ-3 script (N runs, cleanup, exit code).
- [x] Docs: stub and real-provider runs, threshold-tuning procedure.
- [x] Stub stack: UJ-3 3/3; UJ-1 10/10; stack restored to the real config.

**AC:** see `epics-sql-service.md` Story 8.3. Real-model 3x run and committed tuned threshold remain open (model not served, NO-GO).

## Spec Change Log

## Verification

- No Java change, so `mvn verify` not re-run.
- `node scripts/e2e/uj3-rag-lift.mjs` (stub): 3/3 runs passed; each first prompt no-similar with 0/2 tables, follow-up `examples_used=[saved id]`, 2/2 tables.
- `node scripts/e2e/uj1-e2e.mjs` (stub): 10/10 steps passed.
- Restored: sql-service profile `demo`, `HF_MODEL` `defog/sqlcoder-7b-2`, no `HF_BASE_URL`, llm-stub stopped.
