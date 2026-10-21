# Epic 6 Context: Natural Language -> SQL Generation (RAG)

<!-- Compiled from planning artifacts (epics-sql-service.md Epic 6, PRD FR-13..FR-20, FR-36, addendum §A/§B, PROJECT_CONTEXT LLM rules). Edit freely. -->

## Goal

Developers describe what they want in plain English and get validated read-only SQL, grounded in the team's own Indexed Queries. Every failure path is specific, the request finishes inside its deadline, prompts are versioned, and an evaluation harness proves the RAG lift.

## Stories

- 6.1: Generate SQL from a prompt (zero-shot, validated) -- `POST /api/sql/generate`, `LlmClient`, `prompts/` templates
- 6.2: Clear LLM failure handling (FR-19: LLM_UNAVAILABLE + Retry-After, LLM_QUOTA_EXCEEDED, LLM_NOT_CONFIGURED)
- 6.3: One corrective retry, refusal detection (LLM_REFUSED), SQL_PARSE_ERROR, `sql.generation.deadline` 13 s
- 6.4: RAG grounding (top 5 above `sql.rag.threshold`, `<example>` data blocks, FR-15/FR-16 Warnings, `sql.rag.enabled`)
- 6.5: Observability (NFR7 log line), `llm` health indicator (no live calls), gateway response-timeout 15 s
- 6.6: Evaluation harness (SM-2, SM-3)

## Requirements & Constraints

- Request `{prompt, dialect}`: prompt 1-1000 chars; dialect defaults to `postgresql`, any other value 400 `VALIDATION_FAILED`.
- Response (FR-18): `sql, dialect, source: llm-generated, original_prompt, examples_used (never absent), warnings, safety_note, valid: true, prompt_version`.
- Never auto-save, never execute generated SQL. Every reply goes through SQL extraction and the Read-Only Validator.
- Validator-rejected SQL is not retried: 422 `SQL_REJECTED`, details `{rejected_sql, reasons, llm_raw_response, retry_after_seconds}`.
- At most two LLM calls per request; transport failures never retried (`hf.retry-max-attempts: 1`), `hf.timeout` 5 s.
- Prompts live in `src/main/resources/prompts/<version>/`, selected by `sql.prompt.version` (v1); never in controllers. Prompt and example text never logged (NFR5).
- Health never makes live LLM calls. Service boots and CRUD works without `HF_API_TOKEN`.

## Technical Decisions

- Provider: Hugging Face Inference, model `HF_MODEL` (default `defog/sqlcoder-7b-2`, a completion model with the "### Task / ### Database Schema / ### Answer" prompt format). Serving of that model by HF Inference Providers is unverified (risk).
- Package `com.devassisthub.sql.llm`; RestClient + JDK HTTP client, as in the 5.2 `EmbeddingClient`; shared HF failure helpers.
- Error envelope `{error, message, field, details}` via `ApiException`; Safety Note from `validation.SafetyNote`.
