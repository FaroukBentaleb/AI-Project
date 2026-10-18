# Reconciliation: brainstorm (intent + memlog) vs prd.md + addendum.md

Input: `brainstorm-sql-service-2026-10-18/brainstorm-intent.md` and `.memlog.md`.
Superseded ideas are not flagged: execution, auto-save, owner-only filtering, inline schema hint, owner-only edit, non-HF providers, and "never return raw text". None of them was found still present in the PRD.

Severity: **H** = a decision or safety-relevant need is missing or contradicted. **M** = a qualitative need is weakened or a stated item is dropped. **L** = polish or traceability.

---

## High

### G1. The library does not flag non-read-only Saved Queries to the people who copy them
- **Source:** memlog "Text-only still: saved library accumulates dangerous patterns", "teammates reuse a saved bad query without review", "Label: is_llm_generated true signals extra scrutiny". There is also the later decision that the library accepts non-SELECT queries and only indexes the ones that pass the validator.
- **PRD state:** FR-1 stores an UPDATE with `indexed = false`, but FR-27 (library entry) and FR-31 (detail) never show whether a query is read-only. A teammate can copy a saved `UPDATE`/`DELETE` with one click (FR-28) and get no signal. The indexed flag also mixes up "failed the validator" with "Qdrant was down" (FR-1, FR-25).
- **Land in:** §4.1 attributes, FR-27, FR-31, and FR-2 response.
- **Fix:** Add a persisted `read_only` flag (validator outcome plus rejection reason), kept separate from `indexed`. Show a "not read-only" badge in the list and detail views.

### G2. Retry and timeout budget: addendum contradicts FR-17, and HF cold start is not addressed
- **Source:** intent open question "Latency budget: embedding + Qdrant + LLM (5s × up to 2 attempts) vs. the gateway timeout"; memlog "STAR: Is 5s timeout too short for free-tier cold start?"
- **PRD state:** FR-17 says "at most two LLM calls per request". Addendum §A says "transport retries are separate", which allows more calls and puts the 12 s p95 and 15 s gateway limits at risk. The free-tier "model loading" (503 with an estimated time) is never covered. It is the most likely demo failure and is not the same as being down or out of quota.
- **Land in:** FR-17, FR-19, §7 Performance, addendum §A, and §12.
- **Fix:** Pick one hard cap on total HF calls per request, including transport retries, and state it in both the PRD and the addendum. Map the HF model-loading response to `LLM_UNAVAILABLE`, with `Retry-After` taken from the provider's estimate. Add a warm-up call to the demo checklist.

---

## Medium

### G3. 11pm-developer trust signals are missing from the list view, and "no extra clicks" has been weakened
- **Source:** memlog "SQL visible immediately, no extra clicks", "Indicator whether a query was tested/used before", "Show when a query was last used", "Frustration: scrolling through 50 unnamed queries". Intent: "SQL visible in list/detail without extra clicks".
- **PRD state:** FR-27 lists title, excerpt, author, tags, and the deprecated badge. It omits use_count/last_used_at and the LLM-generated badge. "Without extra navigation" is weaker than "without extra clicks", since an expand toggle would satisfy it.
- **Land in:** FR-27 (and FR-29 for the generate result).
- **Fix:** Say the SQL is rendered inline in every list row and result, with no click or expand needed. Add a use count or last-used date and an LLM-generated badge to each entry.

### G4. The "no long form to save" frustration is not captured as a constraint
- **Source:** memlog "Frustration: long form required just to save a query", "Save generated result instantly with just a name", and the retention note that, unlike ChatGPT, there is no save step. The later decision F2 keeps title + description mandatory but does not license a long form.
- **PRD state:** FR-29 prefills the SQL and prompt, but nothing limits the form. Tags, notes, and dialect could all appear as fields the user has to fill.
- **Land in:** FR-29 and FR-1.
- **Fix:** Require that saving from Generate needs exactly two user inputs (title and description), with everything else prefilled or optional and collapsed, and that it can be done from the result view in one step.

### G5. The duplicate-prevention JTBD has no supporting capability
- **Source:** intent primary job 4 "Avoid duplicates"; memlog "JOB: Duplicate prevention: second dev finds first dev's query before rewriting it".
- **PRD state:** The job is mentioned in §2.1 ("Stay consistent ... or duplicates"), but no FR helps with it at save time. Generation's `examples_used` helps only indirectly.
- **Land in:** §4.1 (new Could FR) or FR-29.
- **Fix:** Add a Could item: at save time, show the top similar existing Saved Queries from the RAG Index ("a similar team query exists"). The save is never blocked.

### G6. MoSCoW is presented as decided, but the user skipped MoSCoW
- **Source:** memlog "(direction) User skipped MoSCoW; requested final artifacts".
- **PRD state:** §10 states a firm Must/Should/Could split. One-click copy and recording a use (a top 11pm need, and part of "API behaviours" in the intent) and FR-31 (the only screen that shows UJ-2 trust metadata and deprecation) are both Should, while FR-7 is Must.
- **Land in:** §10 and §13.
- **Fix:** Tag §10 as `[ASSUMPTION: PM-proposed, not confirmed in brainstorm]`. Move copy (FR-28 without recording) and the read-only part of FR-31 to Must.

### G7. Instructor and demo-reliability fixes from Reverse Brainstorming were dropped
- **Source:** memlog FIX "Angular smoke test against sql-service 3+ days before demo" (from "never tested with Angular, only Postman"), "Test LLM on demo network in advance; graceful offline message" (from "no internet at demo"), "logback-spring.xml: INFO in prod, DEBUG on named logger" (from "TRACE logging makes console unreadable").
- **PRD state:** Only the 15-request burst test (SM-5) survives. The addendum §G demo script lacks the other three.
- **Land in:** §11 SM-5, or addendum §G/§F.
- **Fix:** Add to the demo checklist: an end-to-end Angular-through-gateway smoke test at least 3 days out, an LLM reachability check on the demo network, and INFO-level default logging.

### G8. DB indexes from the intent were dropped
- **Source:** intent "Indexes on `created_by`, `created_at`, `is_deleted`. `ddl-auto=validate`"; memlog "FAIL: DATA: no indexes; searches are full scans".
- **PRD state:** Neither the PRD nor the addendum mentions indexes. Only `ddl-auto: validate` survives. The intent also says `original_nl_prompt` is in the same table, which is also unstated.
- **Land in:** addendum §A (or a new data-model section).
- **Fix:** Add "indexes on author/created_by, created_at, deleted flag; original_nl_prompt as a column on the same table; explicit column names verified by a PostgreSQL mapping test."

### G9. "Why Hugging Face" has no rationale beyond "project rule"
- **Source:** memlog "Impress: documented rationale for LLM provider choice vs alternatives"; Q&A "why this LLM provider?"
- **PRD state:** The hard requirements are listed (§6), but addendum §H says only "Other LLM providers: out, per the project rule". That is a weak demo answer.
- **Land in:** §6, or addendum §H/§G.
- **Fix:** Require a short provider-rationale table in the technical report (HF vs 2–3 alternatives scored against the hard requirements) as a deliverable.

### G10. No replacement pointer for deprecated queries
- **Source:** memlog "Adapt: whether a newer version exists", "Avoid: known better alternative".
- **PRD state:** UJ-2 says Leila "would have looked for the replacement", but FR-7 captures only a free-text reason, so there is nothing to follow.
- **Land in:** FR-7 and §4.1 attributes.
- **Fix:** Add an optional `replaced_by` (Saved Query id), shown as a link on deprecated queries.

### G11. The source of the identity value is undefined
- **Source:** memlog "STAR: No auth system exists: how to get userId reliably?", "FAIL: API: require auth headers gateway doesn't forward", "FIX: Document gateway-forwarded headers; dev-mode startup log of received headers".
- **PRD state:** OQ-6 resolves the header name, and the frontend sets `X-User-Id`. But nothing says where the frontend gets the value, so in practice everything may end up attributed to `anonymous`. That weakens "created_by visible (who to ask)".
- **Land in:** §6 API gateway and FR-27/FR-29.
- **Fix:** State how the frontend gets the user id (for example, a name prompted once and stored locally). Add a dev-mode log of the received identity header.

---

## Low

### G12. Being able to show the assembled prompt
- **Source:** intent "Prompt versioned and shown in the demo"; Q&A "show me the prompt".
- **PRD state:** FR-20 logs the template version only.
- **Fix:** Add a Could item: a debug option, or the generate response, that returns the prompt template version and the assembled prompt (with Examples).

### G13. The filter parameter name has drifted
- **Source:** intent `?createdBy=`. The PRD uses `author=`.
- **Fix:** Confirm one name and note the change.

### G14. PII in saved sql_content sent as Examples
- **Source:** memlog "Text-only still: PII in stored/logged query text".
- **PRD state:** The §7 notice covers the prompt box only.
- **Fix:** Show the same "don't include personal data" notice on the save form, because saved SQL is sent to HF as Examples.

### G15. Smaller dropped items
- No owner is named for the function blocklist (memlog "who maintains the function blocklist?").
- The scale trade-off of keeping nl_prompt in the same table was not flagged (memlog STAR).
- The Checkstyle ban on empty catch blocks is missing (memlog FIX).
- The Q&A items "walk through one commit per member" and "two more weeks?" are missing from addendum §G.
- **Fix:** Add a single line for each to addendum §B, §F, or §G.

### G16. Learning-by-example and intent check outside the Generate screen
- **Source:** memlog "Always show original nl_prompt beside SQL so dev confirms intent match", "JOB: Tired: check query intent vs what was written".
- **PRD state:** Shown on Generate and in detail via "all attributes", but not required to sit next to the SQL.
- **Fix:** In FR-31, say original_nl_prompt is shown next to sql_content when present.
