# Epic 6 summary: Natural Language -> SQL Generation (RAG)

`POST /api/sql/generate` turns a plain-English prompt into validated read-only SQL, grounded in the team's Indexed Queries. Every reply goes through extraction and the Read-Only Validator. Nothing is saved or executed by the service. Code lives in `com.devassisthub.sql.llm`, `rag/ExampleRetriever`, `service/GenerationService`, plus the test-scope evaluation harness in `com.devassisthub.sql.eval`.

Final verification (Story 6.6 run): `mvn -f sql-service/pom.xml verify` gave BUILD SUCCESS with 469 surefire + 85 failsafe tests, 0 failures, 1 skipped (`SqlEvalIT`, the opt-in real evaluation). There were no frontend changes in this epic.

## Delivered

- **6.1 Zero-shot generation:** `LlmClient` (HF text-generation, `hf.timeout` 5 s, no transport retries), versioned prompt templates (`prompts/v1/`), SQL extraction, validator, FR-18 response, 422 `SQL_PARSE_ERROR` / `SQL_REJECTED` with stable details.
- **6.2 LLM failure handling:** FR-19 codes `LLM_UNAVAILABLE` (+ `Retry-After` when hinted), `LLM_QUOTA_EXCEEDED`, `LLM_NOT_CONFIGURED`, via `LlmFailureException`.
- **6.3 Corrective retry:** one retry on parse failure/empty reply (`retry.txt`), `LLM_REFUSED` detection, 13 s `sql.generation.deadline` guard.
- **6.4 RAG grounding:** top 5 Indexed Queries above `sql.rag.threshold` (0.5) as `<example>` data blocks, `examples_used`, FR-15/FR-16 Warnings, `sql.rag.enabled` switch; RAG failures degrade to zero-shot.
- **6.5 Observability:** one NFR7 line per generation on `sql.generation` (no prompt/example text, no token), `llm` health indicator from cached last-call state in the `dependencies` group, gateway `response-timeout: 15000` on the sql-service route, `SQL_HF_MODEL` compose variable.
- **6.6 Evaluation harness:** 15 audited held-out prompts, HTTP harness against running instances (RAG on/off URLs), sandbox execution on a throwaway PostgreSQL with `seed/schema.sql` (SELECT-only role, READ ONLY transaction, statement_timeout, rollback), `target/eval-report.md` with rates and lift. Opt-in via `mvn -Peval verify`; offline self-test runs in CI.

## Blocker: generation model availability

A live call in 6.5 with `HF_MODEL=defog/sqlcoder-7b-2` returned a provider error (503 `LLM_UNAVAILABLE`, `llm` health DOWN / PROVIDER_ERROR). The model is most likely not served by HF Inference Providers on the `hf-inference` router. Generation therefore cannot produce SQL end to end today, and SM-2/SM-3 numbers have not been measured. All code paths are covered by MockWebServer tests.

Options (each needs only configuration unless noted):
1. **Dedicated HF Inference Endpoint** for `defog/sqlcoder-7b-2` (paid, GPU). Set `HF_BASE_URL` to the endpoint URL; the prompt format stays as designed. Check the URL shape matches `base-url + /{org}/{name}` or point base URL so the path resolves.
2. **Switch to a served model** through `SQL_HF_MODEL` (e.g. a Qwen2.5-Coder or Llama-3 instruct model offered by a provider). Likely needs a code change: providers mostly expose chat-completions (`/v1/chat/completions`), not the text-generation payload `LlmClient` sends, and the sqlcoder-format template should become a `v2` prompt version. This is a decision for the module owner (changes 6.1's approved provider contract).
3. **Self-host** sqlcoder (TGI/vLLM/Ollama in compose) exposing a text-generation-compatible endpoint, with `HF_BASE_URL` pointed at it. No code change if the payload matches; heavy on local hardware.

Once one of these works, run the evaluation per `spec-6-6-evaluation-harness-sm2-sm3.md` (Verification) and record the numbers in the technical report; tune OQ-7 (`sql.rag.threshold`) at the same time.

## Deferred / risks

- SM-2/SM-3 baseline and OQ-7 threshold tuning are pending the model blocker.
- RAG on/off is a server switch, so the evaluation needs two instances (or a restart); a per-request toggle was not added to avoid an API change.
- Zero-shot (RAG off) prompts carry no schema, so the RAG-off executable rate is expected to be low; the lift partly measures schema grounding, which should be stated in the report.
- The prompt audit test is a keyword-overlap heuristic plus a documented manual audit; re-audit when seeds or prompts change.
- `rag_ms` combines embedding and Qdrant time; a live 13 s through-gateway test was not automated (6.5).
