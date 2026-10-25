---
title: 'Story 5.2: Embedding client'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '7dacc58'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-1-qdrant-collection-bootstrap.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Indexing (5.3), reconciliation (5.4), seeding (5.5) and RAG retrieval need text turned into vectors, and every way Hugging Face can fail must be safe for callers (FR-19, FR-22).

**Approach:** A dedicated `EmbeddingClient` (RestClient + JDK HTTP client, `hf.embedding-timeout` 2 s, one attempt) that POSTs `{"inputs": text}` to the HF feature-extraction pipeline for `hf.embedding-model` with the `HF_API_TOKEN` bearer token, checks the vector length against `hf.embedding-dimension`, and returns a sealed `EmbeddingResult`: `Embedded(float[])` or `Unavailable(cause, detail, retryAfterSeconds)`. It never throws.

## Boundaries & Constraints

**Always:** Token from env only, never logged or in `toString`; no retries; every failure → `Unavailable` with a distinct cause; base URL configurable.

**Never:** No callers wired yet (5.3+); no Qdrant writes; no `llm` health indicator (health never makes live LLM calls; it lands with the generation client); no real HF call from tests; no frontend or other-service change.

## I/O & Edge-Case Matrix

| Scenario | Provider answer | Result cause | FR-19 code |
|----------|-----------------|--------------|------------|
| Success | 200 `[f × 384]` (or `[[f × 384]]`) | `Embedded` | — |
| Wrong size | 200 `[f × 768]` | `DIMENSION_MISMATCH` (ERROR log) | LLM_NOT_CONFIGURED |
| Slow | no headers within 2 s | `TIMEOUT` (~2 s) | LLM_UNAVAILABLE |
| Bad token | 401 / 403 | `INVALID_TOKEN` | LLM_NOT_CONFIGURED |
| Rate limit | 429 | `QUOTA_EXCEEDED` | LLM_QUOTA_EXCEEDED |
| Loading | 503 `{"estimated_time": 20.3}` | `MODEL_LOADING`, retryAfter 21 | LLM_UNAVAILABLE |
| Other 5xx / 4xx | e.g. 500 | `PROVIDER_ERROR` | LLM_UNAVAILABLE |
| Empty body | 200, no body | `EMPTY_RESPONSE` | LLM_UNAVAILABLE |
| Not a vector | object, token-level matrix | `MALFORMED_RESPONSE` | LLM_UNAVAILABLE |
| Connection drop / DNS | — | `UNREACHABLE` | LLM_UNAVAILABLE |
| No token or model | no call | `NOT_CONFIGURED` | LLM_NOT_CONFIGURED |
| Blank text | no call | `EMPTY_INPUT` | LLM_UNAVAILABLE |

## Decisions

- Endpoint: HF Inference Providers, `hf.embedding-base-url` (`HF_EMBEDDING_BASE_URL`, default `https://router.huggingface.co/hf-inference/models`) + `/{org}/{name}/pipeline/feature-extraction`. No other service in the repo calls HF embeddings yet (commit-service `EmbeddingService` is a stub), so nothing to align with beyond the 384-dim default. Model id segments are encoded individually so the `/` stays literal.
- New key `hf.embedding-timeout` (`HF_EMBEDDING_TIMEOUT`, 2s, addendum §A) used as connect and read timeout. Independent of `hf.timeout`/`hf.retry-max-attempts` (generation).
- `EmbeddingProperties` record bound to `hf.` (redacting `toString`); bean built in `QdrantConfig` beside the Qdrant client. Package `rag` (addendum: embedding + Qdrant).
- Each `Cause` exposes the FR-19 code it maps to, so 5.3+ and the generation path can surface `LLM_NOT_CONFIGURED` / `LLM_QUOTA_EXCEEDED` / `LLM_UNAVAILABLE` (+ Retry-After) without re-classifying. Dimension mismatch maps to `LLM_NOT_CONFIGURED` (configuration error).
- A single-row nested answer `[[...]]` is accepted; multi-row (token-level, non-pooled model) is `MALFORMED_RESPONSE`.
- Spring's JDK request factory reports read timeouts as a plain `IOException("Request timed out…")`; timeout detection walks the cause chain for timeout types or that message.
- Compose and `.env.example` already carry `HF_EMBEDDING_MODEL` (done in 5.1); base URL/timeout use defaults, so no compose change.
- Test-only dependency `com.squareup.okhttp3:mockwebserver:4.12.0`.

</frozen-after-approval>

## Code Map

- `sql-service/src/main/java/com/devassisthub/sql/rag/EmbeddingClient.java` -- HTTP call, parsing, classification.
- `.../rag/EmbeddingResult.java` -- sealed result + `Cause` with FR-19 codes.
- `.../rag/EmbeddingProperties.java` -- `hf.` binding, redacted.
- `.../config/QdrantConfig.java` -- `embeddingClient` bean.
- `application.yml` (`hf.embedding-timeout`, `hf.embedding-base-url`), `pom.xml` (mockwebserver).
- Test: `rag/EmbeddingClientTest` (MockWebServer, 17 cases).

## Tasks & Acceptance

**Execution:**
- [x] Config keys + properties (redacted) + bean.
- [x] Client with typed result; no retries; no token logging.
- [x] MockWebServer tests: success, nested, dimension mismatch, timeout, 401, 403, 429, 503 estimated_time, other 5xx, empty body, malformed, token-level, no token, no model, blank text, disconnect, token never logged.
- [x] `mvn verify`.

**Acceptance Criteria:**
- Given a MockWebServer returning a valid vector, when I embed text, then I get the vector, and a dimension mismatch is reported as a configuration error.
- Given a timeout (> 2 s), 401, 403, 429, 503 with `estimated_time`, an empty body, or no token configured, when I embed, then each yields "embedding unavailable" with a distinct cause, within about 2 s.

## Spec Change Log

- Model id path: first draft used a `{model}` URI variable, which encoded `/` as `%2F`; switched to per-segment encoding.
- Timeout classification broadened after Spring surfaced it as `IOException("Request timed out")`.

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- result: BUILD SUCCESS, 330 surefire + 60 failsafe tests green (EmbeddingClientTest stable over 4 runs).

**Manual checks:**
- Not exercised against the real HF API (by design). The default URL shape should be smoke-checked once with a real token before 5.3 demo use.
