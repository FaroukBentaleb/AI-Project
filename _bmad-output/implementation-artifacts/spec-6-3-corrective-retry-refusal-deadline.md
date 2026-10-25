---
title: 'Story 6.3: One corrective retry, refusal detection, and deadline guard'
type: 'feature'
created: '2026-10-20'
status: 'done'
baseline_commit: 'b080caa'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-1-generate-sql-zero-shot.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-2-clear-llm-failure-handling.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 6.1/6.2 make exactly one LLM call, so a single garbled, empty, or refusing reply ends the request, and a prose refusal surfaces as a confusing `SQL_PARSE_ERROR` (FR-17).

**Approach:** After an empty, unparseable, or refusal reply, make one corrective call with a versioned retry template that quotes the unusable reply. Never more than two calls. Track `sql.generation.deadline` (13 s) from request start; skip call 2 when the remaining time is less than `hf.timeout`. Classify the final reply: refusal heuristic → 422 `LLM_REFUSED`, otherwise 422 `SQL_PARSE_ERROR`; both carry `details.llm_raw_response`.

## Boundaries & Constraints

**Always:** At most two outbound calls; transport failures (timeout, unreachable, 5xx, 429, 401/403, malformed body) are final on either call (FR-19, `hf.retry-max-attempts: 1`); validator-rejected SQL is final (`SQL_REJECTED`, no retry); only the final reply is classified; token and prompt never logged.

**Never:** No RAG (6.4), no `llm` health indicator (6.5), no frontend change, no real HF call in tests, no SQL execution.

## I/O & Edge-Case Matrix

| Reply 1 | Reply 2 | Result | Calls |
|---|---|---|---|
| prose / empty / unparseable / refusal | valid SQL | 200 | 2 |
| unparseable or empty | unparseable or empty | 422 `SQL_PARSE_ERROR`, raw = reply 2 | 2 |
| any retryable | refusal | 422 `LLM_REFUSED` "The model declined to generate SQL for this request" | 2 |
| any retryable | non-read-only SQL | 422 `SQL_REJECTED` | 2 |
| any retryable | transport failure | 503 per FR-19 | 2 |
| non-read-only SQL | — | 422 `SQL_REJECTED` | 1 |
| transport failure | — | 503 per FR-19 | 1 |
| retryable, remaining < `hf.timeout` | skipped | reply 1 classified as final (422) | 1 |

## Decisions

- Retry template lives at `prompts/<version>/retry.txt` (v1 + vtest), alongside 6.1's `generate.txt`, instead of the addendum's flat `nl-to-sql-retry.v1.txt` name: same versioning switch (`sql.prompt.version`), no new layout. Placeholders `{{dialect}}`, `{{prompt}}`, `{{previous}}`.
- The previous reply is quoted (capped at 1000 chars, sqlcoder markers stripped, "(empty reply)" if blank) and user/model text is inserted last so it cannot inject placeholders.
- Refusal heuristic (`llm/RefusalDetector`): no line starts with a SQL keyword AND a marker among "I can't/cannot/can not", "not able to", "as an AI", "I won't/will not", "unable to" (case-insensitive, curly apostrophe normalised). Refusal check runs before extraction. A first-reply refusal is retried (FR-17 "empty, unparseable, or a refusal").
- Deadline-skip result follows PRD FR-17 ("fails as if the second reply were also malformed") and the story AC: 422 with reply 1 classified (`SQL_PARSE_ERROR` or `LLM_REFUSED`), not a 503/504. A 504-style code would contradict the approved error-code list.
- "Rejected" output that is retried means parser-rejected (validator returns only `SQL_PARSE_ERROR` reasons); policy rejections stay final per epic-6 context.
- Deadline uses `System.nanoTime()` from the start of `generate`; tests scale it (deadline 1.5 s, `hf.timeout` 1 s, 0.7 s delayed reply 1 ≈ 9 of 13 s). Call 1 needs no guard: `hf.timeout` (5 s) < deadline.
- `LlmClient.timeout()` exposes `hf.timeout` for the guard; no new properties class, `@Value("${sql.generation.deadline:13s}")` on the service.

</frozen-after-approval>

## Code Map

- `service/GenerationService.java` -- attempt/classify loop, deadline guard, `LLM_REFUSED`.
- `llm/RefusalDetector.java` -- heuristic.
- `llm/PromptTemplates.java` -- loads `retry.txt`, `renderRetry`.
- `llm/LlmClient.java` -- `timeout()`.
- `resources/prompts/v1/retry.txt`, `test/resources/prompts/vtest/retry.txt`, `application.yml` (`sql.generation.deadline`).
- Tests: `controller/GenerateRetryTest` (MockWebServer), `llm/RefusalDetectorTest`, `GenerateControllerTest` (no-SQL and empty-body cases now enqueue two replies).

## Tasks & Acceptance

**Execution:**
- [x] Versioned corrective retry template.
- [x] Refusal heuristic + 422 `LLM_REFUSED`.
- [x] One corrective call, two-call cap, transport and validator rejections not retried.
- [x] Deadline guard on call 2.
- [x] MockWebServer tests for every matrix row; `mvn verify`.

**Acceptance Criteria:**
- Given reply 1 prose and reply 2 valid SQL, then 200 after exactly two calls.
- Given both replies unparseable or empty, then 422 `SQL_PARSE_ERROR` with `llm_raw_response`.
- Given the final reply "I can't help with dropping tables", then 422 `LLM_REFUSED` with the FR-17 message.
- Given call 1 used 9 of 13 s (simulated), then call 2 is skipped and 422 returns within the deadline.

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- result: BUILD SUCCESS, 443 surefire + 74 failsafe tests green.
