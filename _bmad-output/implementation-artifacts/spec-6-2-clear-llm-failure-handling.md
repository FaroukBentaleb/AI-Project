---
title: 'Story 6.2: Clear LLM failure handling'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'b080caa'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-1-generate-sql-zero-shot.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-2-embedding-client.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 6.1 maps every LLM failure to 503 with one generic message, so the user cannot tell whether to retry, wait, or give up and save manually (FR-19).

**Approach:** Keep the typed `LlmResult.Failed` causes from 6.1 and map each family to its own 503 code, the EXPERIENCE.md message, and a retry hint (`Retry-After` header + `details.retry_after_seconds`) when the provider gives one. One outbound call per request, no retries.

## Boundaries & Constraints

**Always:** One LLM call per request; provider body and exception text never reach the response or the log; token never logged; service boots and CRUD/validate work with no token; no outbound call at all when no token is set.

**Never:** No retry/refusal/deadline guard (6.3); no `llm` health indicator (6.5); no frontend change; no real HF call in tests; no SQL execution.

## I/O & Edge-Case Matrix

| Provider behaviour | Status | `error` | Retry hint |
|---|---|---|---|
| No answer within `hf.timeout` (5 s) | 503 | `LLM_UNAVAILABLE` | none |
| Connection refused / unreachable | 503 | `LLM_UNAVAILABLE` | none |
| 500 / other non-2xx / plain 503 | 503 | `LLM_UNAVAILABLE` | provider `Retry-After` if numeric |
| 503 `{"estimated_time": 19.4}` or `20` | 503 | `LLM_UNAVAILABLE` | 20 (rounded up) |
| 429 | 503 | `LLM_QUOTA_EXCEEDED` | provider `Retry-After` if numeric |
| 401 / 403 / no token | 503 | `LLM_NOT_CONFIGURED` | never |
| 200 non-JSON / no textual `generated_text` | 503 | `LLM_UNAVAILABLE` | none |
| 200 empty body | 422 | `SQL_PARSE_ERROR` (unchanged from 6.1; 6.3 retries it) | n/a |

Messages (EXPERIENCE.md copy map):
- `LLM_UNAVAILABLE`: "SQL generation temporarily unavailable. You can save a query manually." (PRD FR-19 wording)
- `LLM_QUOTA_EXCEEDED`: "The team's AI quota is used up for now. You can save a query manually."
- `LLM_NOT_CONFIGURED`: "SQL generation isn't configured on this server. You can save a query manually."

## Decisions

- `Retry-After` is sent only when a hint is known (`estimated_time`, else a numeric provider `Retry-After`); with no hint the header is absent and `details.retry_after_seconds` is null. This matches EXPERIENCE.md ("With no hint, *Retry* is enabled at once") and reads FR-19's "with Retry-After" as "when the provider estimates one".
- `LLM_NOT_CONFIGURED` never carries a retry hint, even if a 401/403 response had a `Retry-After`: retrying cannot fix configuration.
- 503 `details` reuses `GenerationErrorDetails` (all four keys, nulls serialized) so every generation error has one shape; `field` is null.
- New `exception/LlmFailureException` (extends `ApiException`) holds the code/message mapping; `GlobalExceptionHandler` adds the header. `LlmResult.Cause` and its FR-19 codes (6.1) are unchanged and consistent with the embedding client (5.2) families.
- Malformed 200 body = provider fault (503 `LLM_UNAVAILABLE`); empty 200 body = empty completion (422 `SQL_PARSE_ERROR`), kept from 6.1 because 6.3's corrective retry covers "empty".
- `HfHttp.retryAfterSeconds` parses delta-seconds only (1..999999); HTTP-date form is ignored.
- Tests use `hf.timeout=1s` with a 6 s MockWebServer delay (the "> 5 s" case) to keep the suite fast; the production default stays 5 s.
- No-token IT does not extend `AbstractIntegrationTest` (its `@DynamicPropertySource` token cannot be overridden by a subclass); it reuses the shared containers, so `QDRANT` became `public`.

</frozen-after-approval>

## Code Map

- `exception/LlmFailureException.java` -- code -> message, retry hint.
- `exception/GlobalExceptionHandler.java` -- 503 + `Retry-After`.
- `llm/LlmClient.java` (`fromStatus` reads provider `Retry-After`), `llm/HfHttp.java` (`retryAfterSeconds`).
- `service/GenerationService.java` -- coarse mapping replaced.
- Tests: `controller/GenerateControllerTest` (MockWebServer: delay, 500 no-leak, 503 estimate x2, plain 503, 429 +/- Retry-After, 401/403, malformed x3, empty body; one call each), `GenerateNoTokenTest`, `GenerateConnectionRefusedTest`, `NoLlmTokenIT` (boot + CRUD + validate + generate with no token).

## Tasks & Acceptance

**Execution:**
- [x] Per-family codes, messages, retry hint; header in handler.
- [x] Provider `Retry-After` passthrough for 429/5xx.
- [x] MockWebServer tests for every FR-19 mode with one-call assertion and no-leak check.
- [x] No-token: WebMvc test (no outbound call, validate works) and full-boot IT (CRUD works).
- [x] `mvn verify`.

**Acceptance Criteria:**
- Given > 5 s delay, 500, 503 `estimated_time: 20`, 429, 401, no token, then respectively `LLM_UNAVAILABLE`, `LLM_UNAVAILABLE`, `LLM_UNAVAILABLE` + `Retry-After: 20`, `LLM_QUOTA_EXCEEDED`, `LLM_NOT_CONFIGURED`, `LLM_NOT_CONFIGURED`, each with its message.
- Given no `HF_API_TOKEN`, the service boots and CRUD works.
- Each case makes exactly one outbound call.

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- result: BUILD SUCCESS, 408 surefire + 74 failsafe tests green.
