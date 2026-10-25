---
title: 'Story 6.1: Generate SQL from a prompt (zero-shot, validated)'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '7dacc58'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-2-embedding-client.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-3-standalone-validate-endpoint.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Developers want a first SQL draft from plain English (FR-13, FR-18), and the reply of a model can never be trusted as-is.

**Approach:** `POST /api/sql/generate` renders the versioned template `prompts/<sql.prompt.version>/generate.txt`, calls a dedicated `LlmClient` (HF text-generation, one attempt, `hf.timeout` 5 s, greedy decoding, capped `max_new_tokens`), extracts the SQL from the completion, runs the Read-Only Validator, and returns the FR-18 shape with `examples_used: []`. Rejected SQL is 422 `SQL_REJECTED`. Nothing is saved or executed.

## Boundaries & Constraints

**Always:** Token from env only, never logged; prompt text never logged; one LLM call per request; every reply goes through extraction + validator; `examples_used` present and empty.

**Never:** No retry, refusal detection or deadline guard (6.3); no fine-grained FR-19 messages/Retry-After (6.2); no RAG (6.4); no `llm` health indicator or NFR7 log line (6.5); no Saved Query write; no SQL execution; no real HF call in tests; no frontend change.

## I/O & Edge-Case Matrix

| Scenario | Input / model reply | Result |
|----------|---------------------|--------|
| Happy path | reply in ```` ```sql ```` fences | 200 FR-18 body, `sql` normalized, `valid: true`, `prompt_version: v1` |
| sqlcoder style | `SELECT ...;\n[/SQL]` or `[SQL] SELECT ... [/SQL]` | 200 |
| Prose around SQL | "Here is the query:\nSELECT ...\n\nThis query counts ..." | 200, prose dropped |
| Non-read-only | `DROP TABLE orders` | 422 `SQL_REJECTED`, `details.rejected_sql`, `reasons`, `llm_raw_response`; one call |
| Hidden 2nd statement | `SELECT 1;\n\nDROP TABLE orders` | 422 `SQL_REJECTED` (MULTIPLE_STATEMENTS) |
| No SQL in reply | "I do not know" / empty | 422 `SQL_PARSE_ERROR` with `llm_raw_response` (no retry yet) |
| Bad request | blank prompt, > 1000 chars, dialect ≠ postgresql | 400 `VALIDATION_FAILED`, no LLM call |
| Missing dialect | `{prompt}` only | dialect `postgresql` |
| LLM failure | timeout, 5xx, 401, 429, no token | 503 with the cause's FR-19 code and a generic manual-save message (refined in 6.2) |

## Decisions

- Model: `hf.model` = `${HF_MODEL:defog/sqlcoder-7b-2}`; a blank value (compose passes `HF_MODEL` empty when unset) also falls back to the default. Note: `HF_MODEL` is shared by every service in compose.
- Endpoint: `hf.base-url` (`HF_BASE_URL`, default `https://router.huggingface.co/hf-inference/models`) + `/{org}/{name}`, text-generation payload `{"inputs", "parameters": {max_new_tokens, do_sample: false, return_full_text: false}}`. "Temperature 0" is expressed as `do_sample: false` (greedy) because TGI rejects `temperature: 0`. Reply `[{"generated_text"}]` or `{"generated_text"}`.
- Defaults aligned with the addendum: `hf.timeout` 10s -> 5s, `hf.retry-max-attempts` 3 -> 1 (the client never retries regardless). New `hf.max-new-tokens: 400`, `sql.prompt.version: v1`. `sql.generation.deadline` is 6.3's.
- v1 template uses the sqlcoder format (`### Task` / `### Instructions` / `### Database Schema` / `### Answer` ... `[SQL]`) with `{{prompt}}` and `{{dialect}}` placeholders; zero-shot, so the schema section says none is provided. Template markers (`[QUESTION]`, `[/QUESTION]`, `[SQL]`, `[/SQL]`, `###`) are stripped from the user prompt before rendering.
- Templates load at startup; a missing template for the configured version fails startup. Version must match `[A-Za-z0-9_-]+`.
- Extraction: first fenced block if any; else cut at `[/SQL]`; start at the first line beginning with an SQL keyword (any statement keyword, so non-read-only output reaches the validator); after a `;`-terminated paragraph, later paragraphs are kept only if they start with an SQL keyword (prose dropped, smuggled statements kept).
- Validator SQL_PARSE_ERROR-only rejection or no SQL found -> 422 `SQL_PARSE_ERROR`; any other rejection -> 422 `SQL_REJECTED`. Details record always carries the four keys (nulls serialized); `llm_raw_response` truncated to 4,000 chars.
- Shared HF helpers (`HfHttp`: timeout detection, `estimated_time` parsing) used by both `EmbeddingClient` and `LlmClient`; `LlmResult.Cause` mirrors the embedding causes with FR-19 codes.
- Safety Note reuses `SafetyNote.TEXT` (4.3).
- Risk: whether `defog/sqlcoder-7b-2` is served by HF Inference Providers is unverified; base URL and model are configurable so a dedicated endpoint can be swapped in.

</frozen-after-approval>

## Code Map

- `llm/LlmClient.java`, `llm/LlmResult.java`, `llm/LlmProperties.java`, `llm/HfHttp.java` -- HF text-generation client.
- `llm/PromptTemplates.java`, `resources/prompts/v1/generate.txt` -- versioned prompts.
- `llm/SqlExtractor.java` -- completion -> SQL.
- `service/GenerationService.java`, `controller/GenerateController.java`, `dto/Generate*.java` -- endpoint.
- `config/LlmConfig.java`, `application.yml`; `rag/EmbeddingClient.java` uses `HfHttp`.
- Tests: `llm/SqlExtractorTest`, `controller/GenerateControllerTest` (MockWebServer; includes nested `sql.prompt.version=vtest` case with `src/test/resources/prompts/vtest/`).

## Tasks & Acceptance

**Execution:**
- [x] Config keys, properties (redacted), client bean.
- [x] LlmClient + shared HF helpers; prompt templates v1.
- [x] Extractor + validation + endpoint + error mapping.
- [x] Tests: happy path, extraction variants, SQL_REJECTED with one call, parse error, request validation, template switch, token never logged.
- [x] `mvn verify`.

**Acceptance Criteria:**
- Given MockWebServer returning SQL in markdown fences, when I POST a valid prompt, then 200 with the FR-18 fields, `examples_used: []`, `valid: true`, `prompt_version`.
- Given a blank prompt, > 1,000 chars, or dialect not postgresql, then 400 `VALIDATION_FAILED`.
- Given the model returns `DROP TABLE orders`, then 422 `SQL_REJECTED` with the rejected SQL and reasons, and no retry.
- No Saved Query is created; switching `sql.prompt.version` changes the template without Java changes.

## Spec Change Log

- Extraction: unterminated SQL followed by prose was not trimmed; added clause-keyword continuation rule (a blank-line paragraph continues the statement only if it starts with FROM/WHERE/JOIN/...).

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- result: BUILD SUCCESS, 393 surefire + 73 failsafe tests green.
