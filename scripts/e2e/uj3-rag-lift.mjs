#!/usr/bin/env node
// Story 8.3: scripted UJ-3 "save, then ask again" RAG-lift test through the gateway.
// Per run: (1) off-domain prompt -> 200 with the no-similar Warning, no Examples used, none of the fixture tables;
// (2) save the corrected SQL (indexed); (3) follow-up prompt -> saved id in examples_used, fixture tables in the SQL;
// (4) soft-delete the saved query, so the next run starts from the same state.
//
//   node scripts/e2e/uj3-rag-lift.mjs                      # stub LLM (docker-compose.e2e.yml), deterministic
//   E2E_MODE=real node scripts/e2e/uj3-rag-lift.mjs        # real HF provider: 2 generations per run, no burst
//
// Env: GATEWAY_URL (default http://localhost:8080), UJ3_RUNS (default 3), UJ3_PAUSE_MS between real calls
// (default 0 stub / 2000 real), E2E_MODE (stub|real). Node 18+, no dependencies. Exit 0 only if every run passes.
import { readFileSync } from 'node:fs';

const GATEWAY = (process.env.GATEWAY_URL || 'http://localhost:8080').replace(/\/$/, '');
const MODE = (process.env.E2E_MODE || 'stub').toLowerCase();
const RUNS = Number(process.env.UJ3_RUNS || 3);
const PAUSE = Number(process.env.UJ3_PAUSE_MS || (MODE === 'real' ? 2000 : 0));
const API = `${GATEWAY}/api/sql`;
const USER = 'e2e-uj3';
const NO_SIMILAR = 'no similar queries found';
const FX = JSON.parse(readFileSync(new URL('./uj3-fixture.json', import.meta.url), 'utf8'));
const TAG = `uj3-${Date.now().toString(36)}`;

async function call(method, path, body) {
  const t0 = Date.now();
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { 'X-User-Id': USER, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, json, text, ms: Date.now() - t0 };
}
const check = (c, m) => { if (!c) throw new Error(m); };
const pause = () => PAUSE && new Promise((r) => setTimeout(r, PAUSE));
const hasTable = (sql, t) => new RegExp(String.raw`\b${t}\b`, 'i').test(sql || '');

async function waitForGateway(timeoutMs = 180000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    try { if ((await call('GET', '/queries?size=1')).status === 200) return; } catch { /* not up */ }
    if (Date.now() > end) throw new Error(`gateway route /api/sql not ready at ${GATEWAY}`);
    await new Promise((r) => setTimeout(r, 3000));
  }
}

async function oneRun(n) {
  let savedId = null;
  try {
    const a = await call('POST', '/generate', { prompt: FX.first_prompt });
    check(a.status === 200, `first: status ${a.status} ${a.text.slice(0, 200)}`);
    check(a.json.warnings.some((w) => w.includes(NO_SIMILAR)), `first: no no-similar Warning ${JSON.stringify(a.json.warnings)}`);
    check(a.json.examples_used.length === 0, `first: examples_used not empty ${JSON.stringify(a.json.examples_used)}`);
    const before = FX.saved.tables.filter((t) => hasTable(a.json.sql, t));
    check(before.length === 0, `first: fixture tables already present (${before}) - fixture is not off-domain`);
    await pause();

    const s = await call('POST', '/queries', { ...FX.saved, tables: undefined, is_llm_generated: false, notes: TAG });
    check(s.status === 201, `save: status ${s.status} ${s.text.slice(0, 200)}`);
    savedId = s.json.id;
    check(s.json.indexed === true, `save: indexed=false ${JSON.stringify(s.json.warnings)}`);

    const b = await call('POST', '/generate', { prompt: FX.follow_up_prompt });
    check(b.status === 200, `follow-up: status ${b.status} ${b.text.slice(0, 200)}`);
    const ids = b.json.examples_used.map((e) => e.id);
    check(ids.includes(savedId), `follow-up: saved id ${savedId} not in examples_used ${JSON.stringify(ids)}`);
    const missing = FX.saved.tables.filter((t) => !hasTable(b.json.sql, t));
    check(missing.length === 0, `follow-up: SQL lacks ${missing}: ${b.json.sql}`);
    console.log(`PASS  run ${n}: first ${a.ms} ms (no-similar, tables 0/${FX.saved.tables.length}) -> saved id=${savedId} -> ` +
      `follow-up ${b.ms} ms (examples_used=${JSON.stringify(ids)}, tables ${FX.saved.tables.length}/${FX.saved.tables.length})`);
    return true;
  } catch (e) {
    console.log(`FAIL  run ${n}: ${e.message}`);
    return false;
  } finally {
    if (savedId) {
      const d = await call('DELETE', `/queries/${savedId}`);
      if (d.status !== 204) console.log(`WARN  run ${n}: cleanup of id ${savedId} -> ${d.status}`);
    }
    await pause();
  }
}

console.log(`UJ-3 RAG lift  mode=${MODE}  gateway=${GATEWAY}  runs=${RUNS}  ${new Date().toISOString()}`);
await waitForGateway();
let passed = 0;
for (let i = 1; i <= RUNS; i++) if (await oneRun(i)) passed++;
console.log(`\n${passed}/${RUNS} runs passed${passed === RUNS ? ' - RAG lift reproducible' : ''}`);
process.exit(passed === RUNS ? 0 : 1);
