#!/usr/bin/env node
// Story 8.2 (SM-5): generation burst through the gateway. Sends N generate requests (default 15) with a
// concurrency limit, then reports p50 / p95 / max latency, failures by error code, and the verdict against the
// active PRD §7 target (p95 <= 10 s normal, <= 15 s Plan B; zero LLM_QUOTA_EXCEEDED; zero failures).
//
//   node scripts/burst-test.mjs                    # normal budget
//   BURST_PLAN=b node scripts/burst-test.mjs       # Plan B budget (docker-compose.planb.yml active)
//
// Env: GATEWAY_URL (http://localhost:8080), SQL_SERVICE_URL (http://localhost:8084, for the model check),
//      BURST_N (15), BURST_CONCURRENCY (5), BURST_PLAN (normal|b), BURST_ALLOW_REAL (unset).
// Safety: the run refuses unless sql-service's model is a stub ("stub/...") or BURST_ALLOW_REAL=1 is set, so the
// shared HF token is never burst by accident. Exit 0 = GO on SM-5, 1 = NO-GO, 2 = could not run.

const GATEWAY = (process.env.GATEWAY_URL || 'http://localhost:8080').replace(/\/$/, '');
const SQL = (process.env.SQL_SERVICE_URL || 'http://localhost:8084').replace(/\/$/, '');
const N = Number(process.env.BURST_N || 15);
const CONC = Math.max(1, Number(process.env.BURST_CONCURRENCY || 5));
const PLAN_B = /^(b|planb|plan-b)$/i.test(process.env.BURST_PLAN || '');
const TARGET_MS = PLAN_B ? 15000 : 10000;
const PROMPTS = [
  'orders per region last week vs the week before',
  'top 5 customers by revenue this month',
  'average order value per month this year',
  'sales by product category last week',
  'customers with no order in the last 90 days'
];

let model = 'unknown';
try {
  const h = await (await fetch(`${SQL}/actuator/health/dependencies`)).json();
  model = h?.components?.llm?.details?.model ?? 'unknown';
} catch {
  /* model check is best effort; unknown counts as real */
}
const isStub = model.startsWith('stub/');
if (!isStub && process.env.BURST_ALLOW_REAL !== '1') {
  console.log(`REFUSED  sql-service model is "${model}", not a stub. Set BURST_ALLOW_REAL=1 for the SM-5 run on the`);
  console.log('         real provider (shared team token: run it once, 24 h before the demo, per docs/sql-service-demo-runbook.md).');
  process.exit(2);
}

console.log(`burst  ${new Date().toISOString()}  n=${N} concurrency=${CONC} plan=${PLAN_B ? 'B' : 'normal'}` +
  ` target p95<=${TARGET_MS} ms  model=${model}  gateway=${GATEWAY}`);

async function one(i) {
  const t0 = Date.now();
  try {
    const res = await fetch(`${GATEWAY}/api/sql/generate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-User-Id': 'burst-test' },
      body: JSON.stringify({ prompt: PROMPTS[i % PROMPTS.length] })
    });
    const text = await res.text();
    let code = res.status === 200 ? 'OK' : `HTTP_${res.status}`;
    try {
      const j = JSON.parse(text);
      if (res.status !== 200 && typeof j?.error === 'string' && /^[A-Z_]+$/.test(j.error)) code = j.error;
    } catch {
      /* gateway timeouts are not JSON */
    }
    if (res.status === 504) code = 'GATEWAY_TIMEOUT';
    return { i, status: res.status, code, ms: Date.now() - t0 };
  } catch (e) {
    return { i, status: 0, code: `NETWORK_${e.cause?.code || e.name}`, ms: Date.now() - t0 };
  }
}

const results = [];
let next = 0;
const t0 = Date.now();
await Promise.all(Array.from({ length: Math.min(CONC, N) }, async () => {
  while (next < N) {
    const r = await one(next++);
    results.push(r);
    console.log(`  #${String(r.i + 1).padStart(2)}  ${String(r.status).padStart(3)}  ${r.code.padEnd(20)} ${r.ms} ms`);
  }
}));
const wall = Date.now() - t0;

const pct = (arr, p) => arr[Math.min(arr.length - 1, Math.ceil((p / 100) * arr.length) - 1)];
const all = results.map((r) => r.ms).sort((a, b) => a - b);
const byCode = {};
for (const r of results) byCode[r.code] = (byCode[r.code] || 0) + 1;
const failures = results.filter((r) => r.code !== 'OK').length;
const quota = byCode.LLM_QUOTA_EXCEEDED || 0;
const p95 = pct(all, 95);

console.log('');
console.log(`latency (all ${N})  p50 ${pct(all, 50)} ms  p95 ${p95} ms  max ${all[all.length - 1]} ms  wall ${wall} ms`);
console.log(`outcomes  ${Object.entries(byCode).map(([k, v]) => `${k}=${v}`).join('  ')}`);
const go = failures === 0 && quota === 0 && p95 <= TARGET_MS;
console.log(`SM-5  ${go ? 'GO' : 'NO-GO'}  (failures ${failures}, quota ${quota}, p95 ${p95} ms vs ${TARGET_MS} ms)`);
process.exit(go ? 0 : 1);
