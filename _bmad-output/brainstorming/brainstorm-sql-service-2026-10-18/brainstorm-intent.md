# sql-service — Brainstorm Intent (input for bmad-prd)

Source: `.memlog.md` in this folder (brainstorm session 2026-10-18, Facilitator mode; techniques: Role Playing, First Principles, Reverse Brainstorming, Job to Be Done; ~385 entries).

## Product in one line
A team library of SQL queries for DevAssist Hub, plus RAG-powered natural-language → read-only SQL generation that improves as the team saves more queries.

## The question the PRD must answer
"Why not just a shared `queries.sql` + Slack?" — the strongest incumbent (history, code proximity, discussion, no infra).
Answer: **the shared library *is* the RAG corpus.** Every saved query makes generation better for the whole team; a text file cannot do that. sql-service must also beat the file on search speed and team visibility.

## Primary jobs (JTBD)
1. Rushed dev: get a runnable first-draft SELECT from plain English, edit rather than write.
2. Save a working query once, find it again fast (search > Ctrl+F).
3. Team knowledge: new joiner / teammate-on-leave / inherited code — find and trust someone else's query.
4. Avoid duplicates; reuse the team's established patterns (e.g. how "last 30 days" is written for our schema).

## Locked decisions
| # | Decision |
|---|---|
| F1 | **Text-only.** Generate, validate, return SQL text. Never execute against any external DB. |
| F2 | **Manual save.** Explicit save; `title` + `description` required. No auto-save. |
| F3 | **Team library.** `GET /queries` returns all saved queries by default (not filtered by `created_by`). |
| F4 | `userId` from gateway-forwarded header; fallback `"anonymous"`. |
| F5 | **Hugging Face** (per PROJECT_CONTEXT.md); model chosen against hard requirements below. |
| F6 | Schema context comes from **RAG** over saved queries; no inline schema-hint field. |
| F7 | **Reject** file/network functions (`pg_read_file`, `pg_read_binary_file`, `lo_export`, `dblink`, `COPY`); **warn** on perf risks (`pg_sleep`, implicit cross join, no `LIMIT`). |
| F8 | 5s LLM timeout; 1 retry on malformed output. LLM-down message: "SQL generation temporarily unavailable. You can save a query manually." |
| RAG | Course requires RAG or fine-tuning → RAG. Embed NL prompt → retrieve top 3–5 similar saved queries (NL + SQL pairs) from Qdrant collection `sql_embeddings` → inject as few-shot examples → generate. |

## Feature 1 — CRUD
Entity fields (derived from "inherited query 6 months later" role):
`id`, `title` (NOT NULL, ≤200), `description` (NOT NULL, mandatory), `sql_content` (TEXT), `tags`, `created_by`, `created_at`, `updated_at` (@UpdateTimestamp), `is_llm_generated`, `original_nl_prompt` (nullable; same table), `dialect` (default PostgreSQL), `last_used_at`, `use_count`, `notes` (author warning/comment), `is_deprecated` + `deprecated_reason`, `is_deleted` + `deleted_at` (soft delete).

API behaviours:
- Pagination `?page=&size=&sort=createdAt,desc`; search `?q=` on title+description; filter `?tags=`, `?createdBy=`.
- PATCH = partial update (non-null fields only); PUT = full replace; documented.
- Soft delete; deleted excluded from lists by default.
- Indexes on `created_by`, `created_at`, `is_deleted`. `ddl-auto=validate`.
- Snake_case JSON everywhere; Bean Validation on DTOs.
- One-click copy, SQL visible in list/detail without extra clicks (frontend).

## Feature 2 — NL → SQL (RAG)
Flow: request `{ prompt, dialect? }` → embed prompt → Qdrant top-k (exclude deleted/deprecated) → build versioned prompt from `prompts/` (schema-by-example from retrieved pairs) → HF LLM (5s, 1 retry on malformed) → validate → structured response.

Validation pipeline (defense-in-depth; documented as **not** a security control):
1. Null/blank check on LLM output (→ 422 `SQL_PARSE_ERROR`).
2. Strip markdown fences; Unicode normalize (NFKC).
3. Parse with **JSqlParser**; reject on parse failure; reject multiple statements.
4. Recursively walk every node (incl. CTE bodies, sub-selects, set operations).
5. Accept only `Select` / `PlainSelect` / `SetOperationList` (UNION/INTERSECT/EXCEPT); reject everything else incl. `SELECT INTO`, data-modifying CTEs.
6. Reject blocklisted file/network functions; attach warnings for perf-risk patterns.

Response: `sql`, `dialect`, `source: "llm-generated"`, `original_prompt`, `examples_used` (ids of retrieved queries), `warnings[]`, `safety_note` ("verified SELECT-only structure; not verified safe or fast on your schema — run as a read-only DB user"), `valid`.

## Error taxonomy (structured body `{error, message, field?}`)
400 `VALIDATION_FAILED` · 404 `NOT_FOUND` · 422 `SQL_PARSE_ERROR` · 422 `SQL_REJECTED` (with reason) · 503 `LLM_UNAVAILABLE` (+ `Retry-After`) · config error for missing HF token · distinct quota-exhausted error. 4xx = user-fixable, 5xx = system. Never a stack trace, never 200-on-error.

## LLM provider — hard requirements (filter for HF model choice)
Free tier covers demo + team dev without card · no spiking per-token cost · plain REST from WebClient · no VPN, works from Tunisia · key in <10 min · GA (not waitlisted) · <5s on short prompt · documented response format · valid PostgreSQL SELECTs · no local GPU.
Soft: handles schema-by-example well, relative dates, aliased columns, joins, playground, consistent output.

## Synthesis — connections to carry into the PRD
1. **Team library (F3) + RAG are one feature.** Visibility is what makes the corpus useful. This is the demo answer to "why not a shared file?"
2. **Mandatory description now pays twice.** Manually saved queries have no `original_nl_prompt`; embed `title + description` (+ prompt when present). Without a mandatory description, the manual half of the corpus is invisible to retrieval.
3. **Corpus poisoning risk.** In a shared library, one bad query gets used as a few-shot example for everyone. Index only queries that pass the validator; drop deprecated or deleted ones from Qdrant; re-validate generated output anyway.
4. **RAG replaces the schema hint, with a gap.** Retrieval only knows tables that appear in saved queries. New tables mean hallucinated names, which was the 11pm developer's top frustration. Add a low-similarity warning ("no similar team queries found; verify table names"). Keeping an optional schema hint is an open question.
5. **Cold start.** An empty corpus gives zero-shot results. Seed 10–20 curated queries for the demo. Strongest demo moment: generate → save → ask a similar question → the better result visibly uses the saved example (`examples_used`).
6. **Qdrant down shouldn't mean failure.** If Qdrant or the embedding call fails, generate zero-shot with a warning. It still answers the instructor's "if X is down, what does the user see?"
7. **Index sync.** Create, update, soft-delete, and deprecate must upsert or remove Qdrant points (point id = query id). A reindex endpoint or startup job is worth having.

## Open questions for the PRD
- Qdrant ownership: PROJECT_CONTEXT.md says commit-service owns Qdrant integration, and `docker-compose.yml` passes `QDRANT_HOST`/`QDRANT_PORT` and `depends_on: qdrant` only to commit-service. sql-service needs both, and the doc needs updating with the team's agreement.
- Embedding model: match commit-service's 384-dim cosine setup (same HF embedding model?) or choose separately?
- Who may update or delete a team query, given there's no auth and an `"anonymous"` fallback? Owner only, or anyone?
- PII: NL prompts and retrieved team examples are sent to HF. The course rule says "never send raw personal data". Decide whether to redact before embedding and generation, and log only the prompt length.
- Latency budget: embedding + Qdrant + LLM (5s × up to 2 attempts) vs. the gateway timeout.
- Is the HF model confirmed against the hard requirements, and burst-tested (15 requests) 24h before the demo?
- `last_used_at` / `use_count`: what counts as a "use" when SQL is text-only (copy? view? generate-from)?

## Out of scope (v1)
Executing SQL · auto-save · inline schema hint / connection strings · conversational refinement ("now add GROUP BY") · comments/discussion threads · fine-tuning.

## Quality & demo bar (from instructor role)
- Unit test for every DBA attack: data-modifying CTE, SELECT INTO, stacked statements, fullwidth SELECT, pg_read_file, dblink, COPY TO, and a legitimate UNION ALL that must pass.
- MockWebServer tests for LLM timeout, 500, and empty body. Testcontainers PostgreSQL, no H2.
- Prompt versioned and shown in the demo.
- 5 demo scenarios: simple select, join, relative date, rejected non-SELECT, LLM-down fallback, plus the RAG improvement moment.
- Rehearse the instructor's Q&A list:
  - DROP TABLE typed into the NL box
  - How the LLM knows table names
  - Show the prompt
  - How this differs from checking for "SELECT"
  - What the user sees when the LLM is down
  - Why Hugging Face
  - A case where the generated SQL is wrong
