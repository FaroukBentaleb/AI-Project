# sql-service end-to-end check (UJ-1)

Story 8.1 / NFR8. Proves the full path browser -> api-gateway -> sql-service -> Qdrant / LLM on `docker compose up`.

## Pieces

- `scripts/e2e/uj1-e2e.mjs`: API-level script through the gateway (Node 18+, no dependencies).
- `docker-compose.e2e.yml`: override that points sql-service's generation and embedding calls at `llm-stub`
  (`scripts/e2e/llm-stub/stub.py`), with a dummy token and its own Qdrant collection `sql_embeddings_e2e`.
  No other service is changed.
- The UI checklist below, plus the Karma specs of `frontend/src/app/features/sql/` (Generate, Save, errors).

## Run against the stub LLM (default, deterministic)

```bash
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build
node scripts/e2e/uj1-e2e.mjs
# afterwards, back to the real provider:
docker compose up -d sql-service api-gateway && docker compose -f docker-compose.yml -f docker-compose.e2e.yml stop llm-stub
```

If you changed `stub.py`'s embedding, drop the old vectors first:
`curl -X DELETE localhost:6333/collections/sql_embeddings_e2e` and restart sql-service.

The stub picks its reply from the question: a normal question gets a SELECT on `orders`; a question with "delete" gets
a DELETE (422 `SQL_REJECTED`); a question with "outage" gets an HF 503 (503 `LLM_UNAVAILABLE`, retry 20 s).

## Run against the real provider

```bash
docker compose up -d --build            # .env holds HF_API_TOKEN, SQL_HF_MODEL, HF_EMBEDDING_MODEL
E2E_MODE=real node scripts/e2e/uj1-e2e.mjs
```

Only the happy path (steps 0-6, 9) runs, without the stub-specific assertions. As of 2026-10-20 this fails at step 1
with `LLM_UNAVAILABLE` while `defog/sqlcoder-7b-2` is not served by HF Inference Providers (`epic-6-summary.md`);
the model choice belongs to the 8.2 go/no-go (`docs/sql-service-demo-runbook.md`).

## Steps

0. Frontend served on :4200; gateway CORS preflight from that origin is allowed.
1. Generate the UJ-1 prompt: SELECT, `safety_note`, Warnings, Examples used (seed queries).
2. Save it (SQL + original prompt, `is_llm_generated`): 201, author, read-only, indexed.
3. Search the library by the run id: found.
4. Copy records usage: `use_count` 1.
5. Validate the saved SQL: accepted, Safety Note.
6. Same prompt again: the saved query is in `examples_used` (RAG).
7. Rejected generation: 422 `SQL_REJECTED` with rejected SQL, reasons and `details.safety_note` (stub only).
8. LLM down: 503 `LLM_UNAVAILABLE` with the PRD copy, then a manual save (stub only).
9. Cleanup: soft-delete the rows created.

## UJ-1 UI checklist (Angular, http://localhost:4200, Edge)

Run on the stub stack after `docker compose ... up -d --build` (the frontend image must be rebuilt to include 8.1).

- [ ] Enter a display name once; reload: it is remembered.
- [ ] *Generate*: type "orders per region last week vs the week before", Ctrl+Enter: "Generating…" indicator shows.
- [ ] Result on the SQL tab; Examples (n) and Warnings (n) counts visible; Safety Note below, outside the tabs.
- [ ] Examples tab: titles link to the query detail.
- [ ] Copy copies the SQL.
- [ ] *Save to library*: SQL and original prompt prefilled; type title + description only; save; confirmation toast; detail opens.
- [ ] Library: the query shows with the LLM-generated badge; Copy increments the use count.
- [ ] Validate: paste the SQL: accepted, Safety Note.
- [ ] Generate "delete the old orders": danger banner, rejected SQL (no Copy/Save), reasons, *Rephrase and retry*, Safety Note.
- [ ] Generate "orders during the outage": "SQL generation temporarily unavailable. You can save a query manually.", *Retry in ~20s* counts down; *Save a query manually* opens the form with the prompt in Notes.

## Run record

| Date | Mode | Result | Notes |
|---|---|---|---|
| 2026-10-20 | stub | 10 / 10 steps passed | generate 203 ms, examples_used 2 (seed), saved query indexed and retrieved on re-ask; rejected and outage paths OK. UI checklist not yet ticked (needs a human in the browser). |
| 2026-10-20 | real | not run | blocked on generation model availability (see above) |

## UJ-3 RAG-lift test (Story 8.3)

`scripts/e2e/uj3-rag-lift.mjs` with fixture `scripts/e2e/uj3-fixture.json` (warehouse shipments / carriers, tables `wh_shipments`, `wh_carriers`, absent from the seed schema and corpus). Each run: off-domain prompt -> no-similar Warning, empty Examples used, no fixture table in the SQL; save the corrected SQL (must be indexed); follow-up prompt -> saved id in `examples_used` and both fixture tables in the SQL; soft-delete. Default 3 runs; exit 0 only if all pass.

Stub (deterministic): the stub echoes the first `<example>` SQL when the prompt has examples and guesses `<word>_data` without them, so the lift is observable.

```
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build llm-stub sql-service
node scripts/e2e/uj3-rag-lift.mjs
docker compose up -d sql-service && docker compose -f docker-compose.yml -f docker-compose.e2e.yml stop llm-stub
```

Real provider (after the 8.2 GO; 2 generations per run, 2 s pause, no burst): `E2E_MODE=real node scripts/e2e/uj3-rag-lift.mjs`. If the first prompt already gets examples, raise `SQL_RAG_THRESHOLD`; if the follow-up misses the saved query, lower it. Restart sql-service between tries, require 3/3 three times in a row, then commit the value as the default `sql.rag.threshold` in `application.yml` (OQ-7).

Run record 2026-10-20 (stub): 3/3 runs passed (first ~25 ms no-similar, follow-up examples_used=[saved id], tables 2/2). Real provider: not run (model not served, NO-GO); threshold stays 0.5.
