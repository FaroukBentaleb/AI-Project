---
stepsCompleted: ["step-01-validate-prerequisites", "step-02-design-epics", "step-03-create-stories"]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-sql-service-2026-10-18/prd.md
  - _bmad-output/planning-artifacts/prds/prd-sql-service-2026-10-18/addendum.md
---

# sql-service (DevAssist Hub) - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for sql-service, decomposing the requirements from the PRD and its technical addendum (which stands in for an architecture document; no separate architecture or UX document exists) into implementable stories.

## Requirements Inventory

### Functional Requirements

**Team Library (CRUD)**
FR1: A user can create a Saved Query with required title, description, and sql_content, plus optional tags, notes, dialect, is_llm_generated, and original_nl_prompt. Returns 201 with Author from X-User-Id (or `anonymous`). The SQL is validated and read_only/read_only_reasons stored. Non-read-only SQL still saves. Indexing completes before 201; if indexing fails, save still succeeds with indexed=false and a Warning.
FR2: A user can view a Saved Query by id. 404 if unknown or soft-deleted.
FR3: A user can browse the Team Library across all Authors. Paginated (default 0/20, created_at desc), size clamped to 100, sort field whitelisted (unknown → 400), deleted hidden, deprecated and non-read-only flagged, paging metadata included.
FR4: A user can search (`q` on title+description, case-insensitive) and filter by tag, Author, is_llm_generated, read_only, deprecated. DB indexes on author, created_at, deleted, tags.
FR5: Any user can edit a Saved Query via PATCH (partial) or PUT (full replace). updated_at changes, Author and created_at immutable. A stale version returns 409 CONFLICT. A sql_content change re-validates. A change to embedded or indexed fields triggers re-indexing.
FR6: Any user can soft-delete (204). The row is kept with deleted/deleted_at, view returns 404 afterwards, and the index point is removed.
FR7: A user can deprecate a Saved Query with a required reason and optional replaced_by, or restore it. Both are idempotent. Invalid replaced_by → 400. Deprecation removes the index point; restore re-applies the indexing rule.
FR8: Copying a Saved Query records a use: use_count+1, last_used_at=now, 204. 404 if unknown or deleted.

**Read-Only Validator**
FR9: Single-statement, recursive structural check. The input is fence-stripped and NFKC-normalized, and the normalized text is what gets validated, returned, and stored. Accepts plain SELECT, set operations of SELECTs, and WITH whose CTE bodies are all SELECTs. Rejects with codes: SQL_PARSE_ERROR, MULTIPLE_STATEMENTS, NON_SELECT_STATEMENT, SELECT_INTO, DATA_MODIFYING_CTE, LOCKING_CLAUSE. Legitimate UNION ALL and CTE comparisons are accepted.
FR10: Function policy. A configurable blocklist (file, large-object, dblink, backend-control, set_config/nextval/setval/advisory locks, query_to_xml) is rejected with BLOCKED_FUNCTION. Warnings (still accepted) on pg_sleep, explicit CROSS JOIN or a comma join with no linking equality, and top-level SELECT * with no LIMIT and no aggregation.
FR11: Validation Result containing accepted flag, Rejection Codes with reasons, Warnings, and normalized SQL. A defined fixture table (attack suite and legitimate cases) plus a parser-coverage set of PostgreSQL idioms must give the expected outcomes.
FR12: Standalone validation endpoint. POST any SQL and get the Validation Result and Safety Note with 200. Blank or oversized input → 400.

**NL→SQL Generation (RAG)**
FR13: Submit an NL Prompt (required, ≤1000 chars) with an optional dialect (postgresql only). No schema-hint field.
FR14: Embed the prompt and retrieve up to 5 Indexed Queries at or above a configurable similarity threshold. Examples are intent text + SQL, each truncated to 2000 chars, total prompt capped, and presented in delimited data blocks.
FR15: When no Example meets the threshold, generate without Examples and attach the Warning "no similar queries found — verify table names in the generated SQL".
FR16: When embedding or Qdrant fails, generate without Examples and attach a RAG-unavailable Warning. RAG failure never causes a 5xx on its own.
FR17: At most two LLM calls per request. Retry only on empty, unparseable, or refusal replies, never on timeout, 429, quota, or 5xx. A deadline guard skips call 2 if it can't fit. Two malformed replies → 422 SQL_PARSE_ERROR with details.llm_raw_response. A refusal → 422 LLM_REFUSED. Validator-rejected SQL → 422 SQL_REJECTED with rejected_sql and reasons (no retry). Deterministic decoding with capped output length.
FR18: Structured response: sql (normalized), dialect, source, original_prompt, examples_used (never absent), warnings, safety_note, valid, prompt_version. Nothing is persisted.
FR19: LLM failure handling:
- timeout, unreachable, 5xx, or model loading → 503 LLM_UNAVAILABLE with Retry-After and the manual-save message;
- quota or 429 → 503 LLM_QUOTA_EXCEEDED;
- missing or invalid token (401/403) → 503 LLM_NOT_CONFIGURED, while CRUD still works.
No stack traces are ever returned.
FR20: Versioned prompt templates (generation and retry) as resources outside Java code. The active version is set by config and logged and returned per generation.

**RAG Index Maintenance**
FR21: On startup, ensure the `sql_embeddings` collection exists, never touching other collections. If Qdrant is down, start anyway, retry every 30s, then run Reconciliation.
FR22: On create, if the indexing rule holds, embed title+description+sql_content (+original_nl_prompt) and upsert a point with id = Saved Query id, before responding. On failure: indexed=false plus a Warning.
FR23: On edit of an embedded or indexed field, re-apply the indexing rule (upsert or remove). Deprecated or deleted queries are never re-indexed by an edit.
FR24: Soft delete and deprecation remove the point. Restore re-applies the rule.
FR25: Reconciliation runs on startup (Must) and after a delayed bootstrap: index missing points, remove orphans, correct the flags. An on-demand admin reindex returns 202 (Should).

**Seed Corpus**
FR26: Seed 10–20 queries over a demo e-commerce schema into sql_db and sql_embeddings at startup. Idempotent by seed_key, all read-only, Author `seed`, can be disabled by config. If Qdrant is down, indexing happens through startup Reconciliation. A demo schema DDL file with sample data is delivered for evaluation.

**Frontend (Angular SQL module)**
FR27: Library screen with browse/search/filter. Entries show title, description excerpt, Author, tags, inline SQL, and badges for LLM-generated, use count, last used, not read-only, and deprecated.
FR28: One-click copy from the list and detail views. Copying a non-read-only query shows a notice. Each copy records a use.
FR29: Generate screen with prompt input showing SQL, Warnings, Safety Note, and Examples used (as links). Save opens a short prefilled form where only title and description are required and optional fields are collapsed, plus a personal-data notice.
FR30: Readable errors. Known codes show their messages. Non-JSON, unknown, or network errors show a generic message. A gateway timeout or any 503 on generation shows a manual-save path.
FR31: Detail and edit screen with all attributes, deprecation reason and replacement link, and validation reasons. Supports edit, deprecate/restore with reason, and delete with confirmation.
FR32: Validate screen. Paste SQL and see the Validation Result and Safety Note.
FR33: Identity. Ask for a display name once, store it locally, and send it as X-User-Id. The user can change it.
FR34 (Could): Show up to 3 similar Indexed Queries at save time.
FR35 (Could): Export Saved Queries as a .sql file with title and description as comments.
FR36: RAG toggle by configuration (sql.rag.enabled) for the SM-3 evaluation.

### NonFunctional Requirements

NFR1: Generation timing budget, default and (Plan B):
- embedding call timeout 2s;
- Qdrant call timeout 1s;
- LLM call timeout 5s (8s), at most 2 calls;
- service deadline 13s (20s);
- gateway route timeout 15s (22s);
- generation p95 ≤10s (≤15s).
NFR2: CRUD reads and validate p95 < 300ms. Create, edit, deprecate, and restore (with synchronous indexing) p95 < 3s.
NFR3: Every outbound call has a timeout, and there are no transport retries on the generation path. RAG failure degrades. A missing or invalid token disables generation and indexing but never CRUD.
NFR4: The HF token comes from the environment only, and .env is git-ignored. Generated SQL is never executed. The validator is documented as defense in depth, not a security control (Safety Note, README, report).
NFR5: Privacy. Prompts and Examples are logged by length and identifiers only. The UI shows a personal-data notice. No redaction.
NFR6: API conventions: global snake_case JSON; strict HTTP semantics; error envelope {error, message, field?, details?}; one global error handler; request validation on every input.
NFR7: Observability. Per generation, log prompt version, Example count, warnings, validation outcome, per-call latencies, and LLM call count. Overall health reflects only the DB. Qdrant and LLM status go in a separate health group with no live LLM calls.
NFR8: Testability (DoD):
- service-layer tests;
- a test for every FR11 fixture;
- every FR19 failure mode tested against a simulated provider (including model loading and invalid token);
- persistence tests on real PostgreSQL with migrations (never H2);
- an end-to-end UJ-1 run through the gateway and Angular at least 3 days before the demo.
NFR9: Course compliance:
- dedicated HF client with strict timeout and retry policy;
- clear missing-key and quota errors;
- output validated before return;
- prompts versioned in prompts/ and documented;
- zero recurring cost.
NFR10: Success metrics require an evaluation harness:
- 15 held-out prompts × RAG on/off × 3 runs;
- outputs executed in a read-only transaction against the seed schema;
- SM-2 ≥80% executable, SM-3 ≥20pp lift, SM-5 burst test with whole team active.

### Additional Requirements

From the addendum (no separate architecture document; the existing scaffold is the starting point, so there's no starter template):
- **Existing scaffold:** Spring Boot 3.3.5, Java 21, Spring MVC, port 8084, Eureka, springdoc, Actuator, package layout controller/service/repository/dto/entity/exception (+ llm/, rag/, validation/, seed/).
- **Flyway** (flyway-core + flyway-database-postgresql). V1__init.sql creates saved_query (with version, seed_key, tags text[], read_only_reasons text[], replaced_by FK) and indexes. Keep ddl-auto: validate.
- **JSqlParser 5.x**, version pinned.
- **Qdrant Java client** (gRPC 6334 or REST 6333). Collection sql_embeddings, cosine, size from config.
- **RestClient** with per-call timeouts (avoid WebClient/webflux).
- **Config keys:**
  - hf.timeout, hf.retry-max-attempts=1, hf.embedding-model, hf.embedding-timeout;
  - qdrant.timeout;
  - sql.generation.deadline;
  - sql.rag.enabled, top-k, threshold;
  - sql.prompt.version;
  - sql.seed.enabled.
- **Jackson SNAKE_CASE.** Health group `dependencies` (qdrant, llm), with the default group containing only db.
- **Indexing rule** as one method, shouldBeIndexed = read_only && !deleted && !deprecated, with syncIndex(q) called by every mutation.
- **Prompt templates:** prompts/nl-to-sql.v1.txt and prompts/nl-to-sql-retry.v1.txt. Decoding with temperature 0 and max_new_tokens ~300.
- **HF response mapping:** 503 with estimated_time → LLM_UNAVAILABLE with retry_after; 401/403 → NOT_CONFIGURED; 429 → QUOTA.
- **Refusal detection heuristic** (no leading SQL keyword plus refusal markers).
- **docker-compose (sql-service block only):** QDRANT_HOST, QDRANT_PORT, HF_EMBEDDING_MODEL, HF_RETRY_MAX_ATTEMPTS=1, depends_on qdrant (service_healthy). Add HF_EMBEDDING_MODEL to .env.example.
- **api-gateway:** route-level response-timeout 15000 on the sql-service route only (owned by the gateway owner).
- **docs/PROJECT_CONTEXT.md:** one line noting the sql-service Qdrant collection.
- **Seed files:** seed/schema.sql (evaluation DB) and seed/seed-queries.json with seed_key, using a consistent "last week" idiom.
- **Testing:** MockWebServer for LLM failure modes; Testcontainers PostgreSQL (+ optional Qdrant).
- **Demo runbook:**
  - Week-6 go/no-go;
  - warm-up calls;
  - INFO logging in the demo profile;
  - validator demo via the Validate screen;
  - LLM-down demo via a dead base URL.
- **CI/CD (user request for this breakdown):**
  - GitHub Actions on push to feature/sql-service-* and on PR to develop;
  - mvn test with real PostgreSQL and Qdrant via Testcontainers;
  - build the Docker image and verify the service starts;
  - on merge to develop, build and tag the image.

### UX Design Requirements

No UX design document exists. Frontend behavior is specified directly in PRD FR27–FR33.

**Confirmed scope decisions (step 1):**
- Angular epics are included, placed after the backend stories they depend on.
- Qdrant via Testcontainers is required for RAG integration tests (and in CI). Unit tests mock the RAG port.

### FR Coverage Map

FR1: Epic 1 (create, attribution); Epic 4 (read_only flag on save); Epic 5 (index on save)
FR2: Epic 1 - view by id
FR3: Epic 1 - browse team library
FR4: Epic 1 - search/filter + DB indexes (read_only filter activated in Epic 4)
FR5: Epic 1 (PATCH/PUT, optimistic locking); Epic 4 (re-validate); Epic 5 (re-index)
FR6: Epic 1 (soft delete); Epic 5 (remove point)
FR7: Epic 1 (deprecate/restore); Epic 5 (index removal/restore)
FR8: Epic 1 - record usage
FR9: Epic 4 - structural read-only check
FR10: Epic 4 - function/pattern policy
FR11: Epic 4 - Validation Result + fixture suite
FR12: Epic 4 - standalone validate endpoint
FR13: Epic 6 - submit NL Prompt
FR14: Epic 6 - retrieve Examples
FR15: Epic 6 - no-similar Warning
FR16: Epic 6 - RAG-unavailable degradation
FR17: Epic 6 - generate/validate/retry, LLM_REFUSED
FR18: Epic 6 - structured response
FR19: Epic 6 - LLM failure handling
FR20: Epic 6 - versioned prompts
FR21: Epic 5 - collection bootstrap
FR22: Epic 5 - index on save
FR23: Epic 5 - re-index on edit
FR24: Epic 5 - remove on delete/deprecate
FR25: Epic 5 - reconciliation (startup Must, admin endpoint Should)
FR26: Epic 5 - seed corpus
FR27: Epic 3 - library screen
FR28: Epic 3 - one-click copy
FR29: Epic 7 - generate screen + save form
FR30: Epic 3 (library errors); Epic 7 (generation errors)
FR31: Epic 3 - detail/edit screen
FR32: Epic 4 - validate screen
FR33: Epic 3 - identity
FR34: Epic 8 - similar queries at save (Could)
FR35: Epic 8 - export (Could)
FR36: Epic 6 - RAG toggle
CI/CD requirements: Epic 2
NFR1, NFR3, NFR7, NFR10: Epic 6 · NFR2, NFR6: Epic 1 (writes budget re-checked in Epic 5) · NFR4: Epics 4 and 6 · NFR5: Epics 6 and 7 · NFR8: Epics 1-7 per story, plus Epic 2 (CI) and Epic 8 (E2E) · NFR9: Epic 6

## Epic List

### Epic 1: Team Library API — save, find, and manage SQL queries
Developers can save, browse, search, view, edit, deprecate, delete, and record use of Saved Queries through the gateway. It starts with the foundation: Flyway V1 schema, global snake_case JSON, the error envelope and handler, and X-User-Id attribution. The service is runnable with no LLM dependency.
**FRs covered:** FR1–FR8 (core). NFR2 reads, NFR6.

### Epic 2: CI/CD Pipeline
Every push to feature/sql-service-* and every PR to develop is tested against real PostgreSQL and Qdrant (Testcontainers). The Docker image is built and its startup verified, and merging to develop builds and tags the image. Included at the user's explicit request.
**Covers:** CI/CD requirements, NFR8 (automation).

### Epic 3: Team Library in the Browser (Angular)
Developers use the library in DevAssist Hub: display-name identity, list with inline SQL and badges, search and filters, one-click copy, detail and edit with deprecate and delete, and readable errors.
**FRs covered:** FR27, FR28, FR30 (library), FR31, FR33.

### Epic 4: Read-Only Validator
Anyone can check whether SQL is structurally read-only, through the API and the Validate screen. Every Saved Query carries a read-only flag and reasons, backed by the full fixture suite.
**FRs covered:** FR9–FR12, FR32, the read-only parts of FR1, FR4, and FR5. NFR4 (Safety Note, README).

### Epic 5: Team Knowledge Index (RAG Corpus)
Read-only, live queries are embedded into sql_embeddings and kept in sync with every change. The index is reconciled at startup, and a seed corpus means it's never empty.
**FRs covered:** FR21–FR26, the indexing parts of FR1, FR5, FR6, and FR7. Compose changes, docs/PROJECT_CONTEXT.md.

### Epic 6: Natural Language → SQL Generation (RAG)
Developers describe what they want in plain English and get validated read-only SQL informed by the team's queries. It includes every failure path, the timing budget, versioned prompts, the RAG toggle, observability, and the evaluation harness.
**FRs covered:** FR13–FR20, FR36. NFR1, NFR3, NFR5, NFR7, NFR9, NFR10. Gateway route timeout coordination.

### Epic 7: Generate in the Browser (Angular)
Developers generate SQL, see Warnings, the Safety Note, and Examples used, and save through the short prefilled form. Every generation failure leads to manual save.
**FRs covered:** FR29, FR30 (generation).

### Epic 8: Demo Readiness and Could-Haves
Make the live demo reliable: end-to-end UJ-1 check, shared-token burst test, warm-up, Week-6 go/no-go and Plan B switch, and a scripted UJ-3 RAG-lift test. Then add the optional features.
**FRs covered:** FR34, FR35 (Could). NFR8 (E2E), SM-5.

---

## Epic 1: Team Library API — save, find, and manage SQL queries

Developers can save, browse, search, view, edit, deprecate, delete, and record use of Saved Queries through the gateway. The service is runnable after every story and has no LLM dependency. Read-only validation (Epic 4) and RAG indexing (Epic 5) attach to these endpoints later.

### Story 1.1: Service foundation — migrations, error contract, identity

As a sql-service developer,
I want the service to boot on a fresh `docker compose up` with versioned schema migrations, a consistent error contract, and caller identity,
So that every later story builds on a runnable, predictable base.

**Implements:** Adds Flyway with `V1__create_saved_query.sql`, containing the core columns: id, title, description, sql_content, tags text[], dialect, notes, author, created_at, updated_at, is_llm_generated, original_nl_prompt, version. It also adds the JPA entity and repository, global snake_case Jackson config, and a `@ControllerAdvice` producing the `{error, message, field?, details?}` envelope. A request-scoped resolver reads `X-User-Id` and falls back to `anonymous`.

**Dependencies:** none (builds on the existing scaffold).

**Acceptance Criteria:**

**Given** an empty `sql_db` created by `infrastructure/postgres/init.sql`
**When** sql-service starts with `ddl-auto: validate`
**Then** Flyway applies V1 and the service starts, registers with Eureka, and `/actuator/health` reports UP
**And** restarting applies no migration twice

**Given** any request that fails Bean Validation or hits an unhandled exception
**When** the global handler processes it
**Then** the body is the snake_case error envelope with the right status (400 `VALIDATION_FAILED`, or 500 with a generic message), and no stack trace is returned

**Given** a request with or without `X-User-Id`
**When** the identity resolver runs
**Then** it yields the header value, or `anonymous` when the header is absent or blank

**And** a Testcontainers PostgreSQL test verifies the migration and the entity mapping (never H2)

### Story 1.2: Save and view a Saved Query

As a developer,
I want to save a SQL query with a title and description and open it again by id,
So that a query that works is never lost.

**Implements:** `POST /api/sql/queries` (FR1, core) and `GET /api/sql/queries/{id}` (FR2), with request validation and the PRD length limits. The Author comes from the identity resolver, and timestamps and version are managed automatically.

**Dependencies:** 1.1.

**Acceptance Criteria:**

**Given** a body with title, description, and sql_content (plus optional tags, notes, dialect, is_llm_generated, original_nl_prompt)
**When** I POST it
**Then** I get 201 with the full Saved Query, including id, author, created_at, updated_at, and version

**Given** a missing or blank title, description, or sql_content, or title > 200, description > 2,000, sql_content > 20,000 chars, or more than 10 tags or a tag > 50 chars
**When** I POST it
**Then** I get 400 `VALIDATION_FAILED` naming the field

**Given** an existing id
**When** I GET it
**Then** I get 200 with all attributes. An unknown id returns 404 `NOT_FOUND`.

**And** a request without `X-User-Id` saves with author `anonymous`
**And** service-layer tests cover the success and validation paths

### Story 1.3: Browse the Team Library

As a developer,
I want to page through every team member's saved queries, newest first,
So that I can see what the team already has.

**Implements:** `GET /api/sql/queries` with pagination and whitelisted sorting (FR3), returning all Authors' queries with paging metadata.

**Dependencies:** 1.2.

**Acceptance Criteria:**

**Given** saved queries by several authors
**When** I GET the list with no parameters
**Then** I get page 0, size 20, sorted by created_at descending, with every author's queries, plus total_elements, total_pages, and page

**Given** `size=500`
**When** I GET the list
**Then** the page size is clamped to 100

**Given** `sort` on a field other than title, created_at, or updated_at (use_count and last_used_at are added in Story 1.8)
**When** I GET the list
**Then** I get 400 `VALIDATION_FAILED`, never 500

### Story 1.4: Search and filter the library

As a developer,
I want to search by words in the title or description and filter by tag, author, or LLM-generated,
So that I find the right query faster than Ctrl+F in a file.

**Implements:** `q`, `tags`, `author`, and `llm_generated` parameters on the list endpoint (FR4), combinable with paging and sorting. Adds `V2__search_indexes.sql` with indexes on author and created_at, and GIN on tags.

**Dependencies:** 1.3.

**Acceptance Criteria:**

**Given** queries titled "Weekly orders by region" and "Top customers"
**When** I search `q=ORDERS`
**Then** only the first is returned (case-insensitive match on title or description)

**Given** queries tagged `reporting` and `ops`
**When** I filter `tags=reporting,finance`
**Then** queries having any of those tags are returned

**Given** `q`, `tags`, `author`, and `llm_generated` together
**When** I GET the list
**Then** all filters apply together (AND), with paging intact

**And** V2 applies cleanly on top of V1

### Story 1.5: Edit a Saved Query safely

As a developer,
I want to fix a teammate's query, either partially or fully, without accidentally wiping fields or overwriting someone else's concurrent edit,
So that the library stays accurate.

**Implements:** `PATCH /api/sql/queries/{id}` (only supplied fields change) and `PUT /api/sql/queries/{id}` (full replace, same validation as create), with optimistic locking via `version` (FR5, core). Any user may edit any query.

**Dependencies:** 1.2.

**Acceptance Criteria:**

**Given** a Saved Query
**When** I PATCH only `tags`
**Then** description and all other fields are unchanged and updated_at advances

**Given** a PUT missing description
**When** I send it
**Then** I get 400 `VALIDATION_FAILED`

**Given** a request carrying a stale `version`
**When** I PATCH or PUT
**Then** I get 409 `CONFLICT` and nothing changes

**And** author and created_at never change, and an unknown id returns 404

### Story 1.6: Delete a Saved Query (soft delete)

As a developer,
I want to remove a query from the library without destroying its history,
So that mistakes are recoverable and deleted queries stop cluttering the list.

**Implements:** `DELETE /api/sql/queries/{id}` (FR6, core) with `V3__soft_delete.sql`, adding deleted, deleted_at, and an index on deleted. All reads and edits exclude deleted rows.

**Dependencies:** 1.4, 1.5.

**Acceptance Criteria:**

**Given** a Saved Query
**When** I DELETE it
**Then** I get 204, the row remains with deleted = true and deleted_at set, and GET by id returns 404

**Given** a deleted query
**When** I browse, search, PATCH, PUT, or DELETE it again
**Then** it is absent from lists, and PATCH, PUT, and DELETE return 404

### Story 1.7: Deprecate and restore a Saved Query

As a developer,
I want to mark a query as no longer recommended, with a reason and a pointer to its replacement,
So that whoever finds it later knows to use something else.

**Implements:** `PUT` and `DELETE /api/sql/queries/{id}/deprecation` (FR7, core) with `V4__deprecation.sql`, adding deprecated, deprecated_reason, deprecated_at, and replaced_by FK. Adds the `deprecated` list filter.

**Dependencies:** 1.6.

**Acceptance Criteria:**

**Given** a Saved Query
**When** I deprecate it with a reason and an optional replaced_by
**Then** I get 200, and the query shows deprecated = true with the reason, timestamp, and replacement

**Given** no reason, or a replaced_by that is unknown, deleted, or the query itself
**When** I deprecate
**Then** I get 400 `VALIDATION_FAILED`

**Given** an already deprecated query, or a non-deprecated query being restored
**When** I repeat the call
**Then** I get 200 (idempotent): the reason and replacement are updated, or nothing changes

**And** deprecated queries still appear in the list, and `deprecated=true|false` filters them

### Story 1.8: Record usage of a Saved Query

As a developer,
I want each copy of a query to be counted,
So that teammates can see which queries are actually used and trusted.

**Implements:** `POST /api/sql/queries/{id}/usage` (FR8) with `V5__usage.sql`, adding use_count (default 0) and last_used_at. Adds use_count and last_used_at to the sort whitelist.

**Dependencies:** 1.6.

**Acceptance Criteria:**

**Given** a Saved Query with use_count 0
**When** I POST usage twice
**Then** each call returns 204, use_count is 2, and last_used_at is set to the latest call

**Given** an unknown or deleted id
**When** I POST usage
**Then** I get 404 `NOT_FOUND`

**And** sorting by use_count and last_used_at works
**And** reads (view, browse, search) meet p95 < 300 ms with a seed-sized dataset (NFR2), measured in an integration test

---

## Epic 2: CI/CD Pipeline

Every push to a `feature/sql-service-*` branch and every PR to `develop` runs the test suite against real PostgreSQL and Qdrant (Testcontainers), builds the Docker image, and verifies the service starts. Merging to `develop` builds and tags the image. Every later story is then protected by the pipeline.

### Story 2.1: Test workflow on feature branches and PRs to develop

As a sql-service developer,
I want every push to my feature branch and every PR to develop to run the full test suite automatically,
So that broken code never reaches develop.

**Implements:** `.github/workflows/sql-service-ci.yml`, triggered on `push` to `feature/sql-service-*` and on `pull_request` targeting `develop`, with a path filter on `sql-service/**` and the workflow file itself. It sets up Temurin 21 with Maven caching and runs `mvn -B verify` in `sql-service/`. Testcontainers PostgreSQL runs on the GitHub-hosted runner's Docker.

**Dependencies:** 1.1 (needs at least one Testcontainers test). It is best done right after Epic 1. It's numbered as Epic 2 and can start any time after 1.1.

**Acceptance Criteria:**

**Given** a push to `feature/sql-service-crud`
**When** the workflow runs
**Then** `mvn verify` executes all tests, including the Testcontainers PostgreSQL tests, and the job fails if any test fails

**Given** a PR targeting `develop` that changes only files outside `sql-service/`
**When** it is opened
**Then** the sql-service workflow does not run

**And** surefire and failsafe reports are uploaded as artifacts on failure
**And** the Maven dependency cache makes repeat runs measurably faster than the first

### Story 2.2: Qdrant Testcontainer available to the test suite

As a sql-service developer,
I want a reusable test base that starts real PostgreSQL and real Qdrant containers,
So that RAG integration tests (Epic 5) run against the real thing in CI and locally.

**Implements:** Adds the Testcontainers `qdrant` module (version pinned to match the compose image), plus an abstract integration-test base that starts PostgreSQL and Qdrant once per test run and injects their connection properties through `@DynamicPropertySource`. A smoke test checks that Qdrant answers a collections-list call. Unit tests keep mocking the RAG port.

**Dependencies:** 2.1.

**Acceptance Criteria:**

**Given** the integration-test base
**When** `mvn verify` runs locally or in CI
**Then** PostgreSQL and Qdrant containers start once and are shared across integration tests

**Given** the Qdrant smoke test
**When** it runs
**Then** it lists collections successfully against the container

**And** unit tests (no `*IT` suffix) start no containers

### Story 2.3: Build the Docker image and verify the service starts

As a sql-service developer,
I want CI to build the real Docker image and prove the container boots healthy,
So that the image we demo is known to start, not just the tests.

**Implements:** Adds an `image-smoke` job after the test job. It builds `sql-service/Dockerfile`, starts a PostgreSQL service container with `sql_db`, runs the image with Eureka disabled (`EUREKA_CLIENT_ENABLED=false`) and Qdrant/HF settings pointing to unreachable or empty values, then polls `/actuator/health` until UP or a 90 s timeout. Container logs are dumped on failure.

**Dependencies:** 2.1.

**Acceptance Criteria:**

**Given** a PR to develop
**When** the image-smoke job runs
**Then** the image builds and the container reports `/actuator/health` UP within 90 s, with Flyway migrations applied

**Given** the service fails to start
**When** the job times out
**Then** the job fails and the container logs are printed

**And** the service starting without Qdrant or an HF token is part of this check, consistent with FR19 and FR21. That part becomes meaningful once those stories exist.

### Story 2.4: Build and tag the image on merge to develop

As the team,
I want every merge to develop to produce a tagged sql-service image,
So that the latest integrated build is always available for the shared environment and the demo.

**Implements:** Adds a `publish` job that runs on `push` to `develop` (that is, after a merge) when `sql-service/**` changed. It builds the image and tags it `sql-service:develop` and `sql-service:sha-<short-sha>`, then pushes to GitHub Container Registry using the built-in `GITHUB_TOKEN` with `packages: write`. `[ASSUMPTION: GHCR is the registry. Confirm with the team before enabling the push.]`

**Dependencies:** 2.3.

**Acceptance Criteria:**

**Given** a PR merged into develop that touches `sql-service/**`
**When** the workflow runs
**Then** tests and image-smoke run first, and only if they pass is the image built and tagged `develop` and `sha-<short-sha>`

**Given** pushes to feature branches or PR builds
**When** the workflow runs
**Then** no image is published

**And** the published image tags are shown in the job summary

---

## Epic 3: Team Library in the Browser (Angular)

Developers use the Team Library in DevAssist Hub through the gateway: a display-name identity, a list with inline SQL and badges, search and filters, one-click copy, manual save, and the detail/edit screen with deprecate and delete. Work happens in `frontend/src/app/features/sql/`.

### Story 3.1: SQL module shell, identity, and error handling

As a developer,
I want the SQL module to know who I am and to show clear messages when something goes wrong,
So that my saved queries are attributed to me and failures are understandable.

**Implements:** Routes for the SQL module (library, detail, new). An `SqlApiService` calls `/api/sql/**` through the gateway. An HTTP interceptor adds `X-User-Id` from a display name asked for once and stored in `localStorage`, changeable from the module header (FR33). A shared error mapper shows known error codes as their message, and non-JSON, unknown, or network errors as a generic message (FR30, library part).

**Dependencies:** 1.2.

**Acceptance Criteria:**

**Given** a first visit with no stored display name
**When** I open the SQL module
**Then** I'm asked for a display name once, it's stored locally, and every request carries it as `X-User-Id`

**Given** a stored name
**When** I change it from the header
**Then** subsequent requests use the new value

**Given** an API error with a known `error` code, or a non-JSON or network failure
**When** it reaches the UI
**Then** the known message is shown, or a generic "service unavailable" message

**And** the module loads through the gateway route `/api/sql/**` (CORS handled by the gateway)

### Story 3.2: Library screen with search, filters, and badges

As a developer,
I want to browse and search the team's queries with the SQL visible inline,
So that I can find and judge a query without clicking into it.

**Implements:** The library list (FR27): paged results, search box (`q`), tag/author/LLM-generated/deprecated filters, and sorting. Each entry shows title, description excerpt, author, tags, the SQL inline (monospace, scroll if long), and badges for LLM-generated, deprecated, use count, and last used.

**Dependencies:** 3.1, 1.4, 1.7, 1.8.

**Acceptance Criteria:**

**Given** saved queries exist
**When** I open the library
**Then** I see 20 per page, newest first, each with its SQL visible without any click

**Given** a search term and filters
**When** I apply them
**Then** the list updates from the API with the combined filters and paging resets to page 1

**Given** a deprecated or LLM-generated query
**When** it is listed
**Then** it shows the corresponding badge, and use count and last used are displayed

**And** an empty result shows an empty state, not a blank screen

### Story 3.3: One-click copy with usage recording

As a developer,
I want to copy a query's SQL in one click,
So that I can paste it into my DB client immediately at 11pm.

**Implements:** A copy button on every list entry and on the detail screen (FR28). It copies the SQL to the clipboard, shows a brief confirmation, and calls `POST /usage`. A usage-call failure never blocks the copy.

**Dependencies:** 3.2, 1.8.

**Acceptance Criteria:**

**Given** a listed query
**When** I click Copy
**Then** the exact SQL is on my clipboard, a "Copied" confirmation appears, and use count increases on refresh

**Given** the usage call fails
**When** I click Copy
**Then** the SQL is still copied and no error blocks me

### Story 3.4: Save a query manually

As a developer,
I want a short form to save a query I wrote by hand,
So that saving takes seconds, not a long form.

**Implements:** A "New query" form: SQL, title, and description are required. Tags, notes, and dialect are in a collapsed "More" section. Client-side validation mirrors the API limits, and server 400 field errors are shown inline. On success it navigates to the new query's detail.

**Dependencies:** 3.1, 1.2.

**Acceptance Criteria:**

**Given** the New query form
**When** I enter SQL, title, and description and submit
**Then** the query is created (201) and I land on its detail view

**Given** a missing title or description, or an over-length field
**When** I submit
**Then** the form shows the field error (client-side or from the 400 `field`) and nothing is saved

**And** optional fields are collapsed by default

### Story 3.5: Query detail, edit, deprecate, and delete

As a developer who inherited a query,
I want to see everything about it and fix, deprecate, or delete it,
So that I can trust it, and keep the library clean for the next person.

**Implements:** The detail screen (FR31) shows all attributes: author, dates, LLM-generated with original prompt, use count and last used, notes, and deprecation reason with a replacement link. Editing uses PATCH with `version`, and a 409 conflict shows a "changed by someone else, reload" message. Deprecate and restore take a reason and an optional replacement. Delete asks for confirmation.

**Dependencies:** 3.3, 3.4, 1.5, 1.6, 1.7.

**Acceptance Criteria:**

**Given** a Saved Query
**When** I open its detail
**Then** every attribute listed in FR31 is visible, including the original prompt when LLM-generated

**Given** I edit and another user saved first
**When** I submit
**Then** I see the conflict message and can reload without losing my edits from view

**Given** I deprecate with a reason and a replacement
**When** I confirm
**Then** the detail shows the reason and a link to the replacement, and the list shows the deprecated badge

**Given** I click Delete
**When** I confirm the dialog
**Then** the query is deleted and I return to the library. Cancelling does nothing.

---

## Epic 4: Read-Only Validator

Anyone can check whether SQL is structurally read-only through the API and the Validate screen. Every Saved Query carries a read-only flag and reasons. Validation runs on syntax trees from a pinned JSqlParser, and the full PRD fixture suite proves it.

### Story 4.1: Structural read-only check

As a developer,
I want SQL classified as read-only only when every statement node in its tree is a SELECT variant,
So that writes hidden in CTEs, INTO clauses, or stacked statements are caught.

**Implements:** A `validation/` component (FR9) with JSqlParser 5.x pinned. Pre-processing strips markdown fences and applies NFKC normalization, and the normalized text is the output. It checks for a single statement, then walks the whole tree recursively (set operations, every CTE body, sub-selects in FROM/WHERE/SELECT/JOIN). It returns Rejection Codes `SQL_PARSE_ERROR`, `MULTIPLE_STATEMENTS`, `NON_SELECT_STATEMENT`, `SELECT_INTO`, `DATA_MODIFYING_CTE`, and `LOCKING_CLAUSE`.

**Dependencies:** 1.1.

**Acceptance Criteria:**

**Given** a plain SELECT, a UNION ALL of SELECTs, or a WITH whose bodies are all SELECTs
**When** validated
**Then** it is accepted, and the normalized SQL is returned

**Given** `WITH x AS (INSERT …) SELECT 1`, `SELECT * INTO t FROM orders`, `SELECT 1; DROP TABLE orders`, `SELECT 1; ＤＲＯＰ ＴＡＢＬＥ orders`, `SELECT … FOR UPDATE`, an UPDATE, or `COPY …`
**When** validated
**Then** each is rejected with the code in the FR11 table (where the PRD allows it, `SQL_PARSE_ERROR` is also acceptable)

**Given** fullwidth `ＳＥＬＥＣＴ 1`
**When** validated
**Then** it is accepted and `SELECT 1` is returned

**And** all cases are parameterized unit tests

### Story 4.2: Function and pattern policy

As a developer,
I want dangerous built-in functions rejected and specific performance risks flagged,
So that file access, remote connections, and session tampering never pass as "read-only".

**Implements:** The FR10 policy. A configurable blocklist (`sql.validator.blocked-functions`) defaults to the PRD list and is matched case-insensitively, with any schema prefix stripped, producing `BLOCKED_FUNCTION` that names the function. Warnings cover `pg_sleep`, explicit `CROSS JOIN` or a comma join without a linking column equality, and top-level `SELECT *` without LIMIT or aggregation.

**Dependencies:** 4.1.

**Acceptance Criteria:**

**Given** `SELECT pg_read_file('/etc/passwd')` or `SELECT dblink('…','DROP TABLE orders')`
**When** validated
**Then** it is rejected with `BLOCKED_FUNCTION` naming the function

**Given** `SELECT pg_sleep(60)`
**When** validated
**Then** it is accepted with a performance Warning

**Given** `FROM orders o, customers c WHERE o.customer_id = c.id`
**When** validated
**Then** it is accepted with no cross-join Warning, while `FROM orders, customers` without such a predicate gets the Warning

**Given** an aggregate report (`SELECT region, count(*) … GROUP BY region`) with no LIMIT
**When** validated
**Then** it produces no Warning (SM-C3)

### Story 4.3: Standalone validate endpoint and fixture suite

As a developer preparing the demo,
I want to paste any SQL into an endpoint and get its Validation Result with the Safety Note,
So that I can show exactly how the validator differs from "checks for SELECT".

**Implements:** `POST /api/sql/validate` (FR12) returns `{accepted, normalized_sql, rejections[{code, reason}], warnings[], safety_note}` with 200. The complete FR11 fixture table and a parser-coverage set (window functions, `FILTER (WHERE …)`, `DISTINCT ON`, interval arithmetic) run as tests through the service. The README gains the Safety Note and the "run generated SQL as a read-only DB user" guidance (NFR4).

**Dependencies:** 4.2.

**Acceptance Criteria:**

**Given** each FR11 fixture
**When** POSTed to `/validate`
**Then** the response is 200 and `accepted` and `rejections` match the FR11 table

**Given** every parser-coverage idiom
**When** validated
**Then** each is accepted (SM-1, SM-C1)

**Given** a blank body or sql_content over 20,000 chars
**When** POSTed
**Then** the response is 400 `VALIDATION_FAILED`

**And** validate meets p95 < 300 ms (NFR2)

### Story 4.4: Read-only flag on Saved Queries

As a developer browsing the library,
I want every saved query labelled as read-only or not, with reasons,
So that I never copy a saved UPDATE thinking it's a harmless SELECT.

**Implements:** `V6__read_only.sql` adds read_only and read_only_reasons. Create and any sql_content change run the validator and store the normalized sql_content, the flag, and the reasons (FR1, FR5 parts). Non-read-only SQL still saves with 201. The `read_only` list filter becomes active (FR4). A one-time startup backfill validates existing rows where read_only is null.

**Dependencies:** 4.3, 1.5.

**Acceptance Criteria:**

**Given** I save `UPDATE orders SET status='x'`
**When** the request completes
**Then** I get 201 with read_only = false and reasons containing `NON_SELECT_STATEMENT`

**Given** I PATCH a read-only query's sql_content to a DELETE
**When** saved
**Then** read_only becomes false with reasons. Changing only tags doesn't re-validate.

**Given** rows saved before this story
**When** the service starts
**Then** each gets a read_only value, and `read_only=true|false` filters the list

### Story 4.5: Validate screen and not-read-only signals in the UI

As a developer,
I want a Validate screen and clear "not read-only" warnings in the library,
So that the validator is visible in the demo and in everyday use.

**Implements:** A Validate screen (FR32): paste SQL, see accepted/rejected, each Rejection Code with reason, Warnings, and the Safety Note. The library and detail screens gain a "not read-only" badge (FR27). The detail shows validation reasons (FR31). Copying a non-read-only query shows a notice (FR28).

**Dependencies:** 4.3, 4.4, 3.3, 3.5.

**Acceptance Criteria:**

**Given** I paste `WITH x AS (INSERT …) SELECT 1` into the Validate screen
**When** I submit
**Then** I see "Rejected", the `DATA_MODIFYING_CTE` reason, and the Safety Note

**Given** a legitimate UNION ALL week-over-week query
**When** validated on screen
**Then** I see "Accepted" and any Warnings

**Given** a saved non-read-only query
**When** it is listed, opened, or copied
**Then** the badge is shown, the detail lists the reasons, and the copy shows the not-read-only notice

---

## Epic 5: Team Knowledge Index (RAG Corpus)

Read-only, live Saved Queries are embedded into the `sql_embeddings` collection in the shared Qdrant and kept in exact sync with every change. The index is reconciled at startup, and a seed corpus means it's never empty. CRUD keeps working when Qdrant or embeddings are down.

### Story 5.1: Connect to Qdrant and bootstrap the collection

As the sql-service owner,
I want the service to create its own `sql_embeddings` collection in the shared Qdrant on startup,
So that RAG has a home without touching commit-service's data.

**Implements:** The Qdrant client config (`QDRANT_HOST`, `QDRANT_PORT`, 1 s timeout) and the FR21 bootstrap: create `sql_embeddings` (cosine, size from config) if missing. If Qdrant is unreachable, the service starts anyway and retries every 30 s. Also:
- the `docker-compose.yml` sql-service block gets `QDRANT_HOST`, `QDRANT_PORT`, and `depends_on: qdrant` (service_healthy);
- a Qdrant indicator is added to a separate `dependencies` health group;
- a one-line note goes into `docs/PROJECT_CONTEXT.md`.

**Dependencies:** 2.2, 1.1.

**Acceptance Criteria:**

**Given** an empty Qdrant (Testcontainers)
**When** the service starts
**Then** `sql_embeddings` exists with the configured size and cosine distance. A restart leaves it and its points intact.

**Given** another collection exists (for example commit-service's)
**When** the service starts
**Then** that collection is untouched

**Given** Qdrant is down at startup
**When** the service starts
**Then** it boots, `/actuator/health` stays UP, `/actuator/health/dependencies` shows Qdrant DOWN, and the bootstrap succeeds within 30 s of Qdrant coming up

### Story 5.2: Embedding client

As the sql-service owner,
I want a dedicated client that turns text into vectors through Hugging Face,
So that queries and prompts can be compared by meaning.

**Implements:** An `EmbeddingClient` using `RestClient` against the HF feature-extraction endpoint for `HF_EMBEDDING_MODEL` with `HF_API_TOKEN`, a 2 s timeout, and no retries. It returns a float vector and checks its dimension matches the collection. A missing or invalid token, timeout, 429, or 5xx surfaces as a typed "embedding unavailable" result, never an exception leaking to callers. `HF_EMBEDDING_MODEL` is added to the sql-service compose block and `.env.example`.

**Dependencies:** 5.1.

**Acceptance Criteria:**

**Given** a MockWebServer returning a valid vector
**When** I embed text
**Then** I get the vector, and a dimension mismatch is reported as a configuration error

**Given** MockWebServer simulating a timeout (> 2 s), 401, 429, 503 with `estimated_time`, an empty body, or no token configured
**When** I embed
**Then** each yields "embedding unavailable" with a distinct cause, within about 2 s

### Story 5.3: Index on save and keep the index in sync

As a developer,
I want every read-only query I save to immediately teach the generator, and withdrawn queries to stop influencing it,
So that RAG always reflects the team's current, trusted knowledge.

**Implements:** `V7__indexed.sql` adds `indexed`. One `shouldBeIndexed(q) = read_only && !deleted && !deprecated` and one `syncIndex(q)` (upsert embedded `title + description + sql_content [+ original_nl_prompt]` with id = Saved Query id and the payload, or delete the point). These are called synchronously by create, edit of embedded or payload fields, delete, deprecate, and restore (FR22–FR24, plus the indexing parts of FR1, FR5, FR6, FR7). A failure keeps the DB change, sets indexed = false, and adds a Warning to the response.

**Dependencies:** 5.2, 4.4, 1.7.

**Acceptance Criteria:**

**Given** I save a read-only query (Testcontainers Qdrant, mocked embedding)
**When** the 201 returns
**Then** exactly one point with id = query id exists and indexed = true

**Given** I save an UPDATE
**When** saved
**Then** no point exists and indexed = false

**Given** an indexed query
**When** I deprecate it, delete it, or edit its SQL into a DELETE
**Then** the point is removed. Restoring the deprecation re-indexes it. Editing the description of a deprecated query does not re-index it.

**Given** Qdrant is stopped
**When** I save a read-only query
**Then** I get 201 with indexed = false and a Warning

**And** create and edit meet p95 < 3 s with mocked embedding latency of 1 s (NFR2)

### Story 5.4: Reconciliation on startup and on demand

As the sql-service owner,
I want the index to heal itself after any outage,
So that a Qdrant hiccup never leaves the RAG corpus silently incomplete.

**Implements:** FR25. A reconciliation run indexes every qualifying query with indexed = false, removes orphan points (ids not qualifying or not in the DB), and corrects flags. It runs on startup (Must) and after a delayed bootstrap succeeds. `POST /api/sql/admin/reindex` (Should) triggers it asynchronously and returns 202, or 503 if Qdrant is unreachable.

**Dependencies:** 5.3.

**Acceptance Criteria:**

**Given** three qualifying queries saved while Qdrant was down, and one orphan point
**When** the service restarts with Qdrant up
**Then** all three are indexed (indexed = true) and the orphan is removed

**Given** Qdrant comes up after a startup outage
**When** the delayed bootstrap succeeds
**Then** reconciliation runs automatically

**Given** a call to `/admin/reindex`
**When** Qdrant is up, or down
**Then** it returns 202 and reconciles, or returns 503 in the standard error envelope with a "RAG index unavailable" message

### Story 5.5: Seed corpus on startup

As the demo presenter,
I want 10–20 curated example queries loaded and indexed automatically,
So that the library and RAG are never empty on a fresh environment.

**Implements:** FR26:
- `V8__seed_key.sql` adds a unique, nullable `seed_key`;
- `seed/seed-queries.json` holds 10–20 entries over the demo e-commerce schema, using one consistent "last week" idiom, UNION ALL and CTE week-over-week variants, a window function, and `FILTER (WHERE …)`;
- `seed/schema.sql` holds the DDL plus sample data for evaluation.

The seeder inserts entries missing by `seed_key` with author `seed`, validates them, and indexes them (or leaves indexing to reconciliation if Qdrant is down). `sql.seed.enabled` turns it off.

**Dependencies:** 5.4.

**Acceptance Criteria:**

**Given** a fresh environment
**When** the service starts
**Then** 10–20 seed queries exist with author `seed`, complete title/description/tags, read_only = true, and indexed = true

**Given** the service restarts
**When** seeding runs again
**Then** no duplicates are created

**Given** `sql.seed.enabled=false`
**When** the service starts
**Then** nothing is seeded

**And** every seed query executes without error against `seed/schema.sql` in a Testcontainers PostgreSQL

---

## Epic 6: Natural Language → SQL Generation (RAG)

Developers describe what they want in plain English and get validated read-only SQL, informed by the team's own queries. Every failure path is specific, the request always finishes inside its deadline, prompts are versioned, and an evaluation harness proves the RAG lift.

### Story 6.1: Generate SQL from a prompt (zero-shot, validated)

As a developer,
I want to type what I need in plain English and get back validated read-only SQL,
So that I get a first draft instead of writing from scratch.

**Implements:** `POST /api/sql/generate` (FR13), an `LlmClient` (RestClient, `HF_API_TOKEN`/`HF_MODEL`, 5 s timeout, no transport retries, temperature 0, capped `max_new_tokens`), and versioned templates in `src/main/resources/prompts/` selected by `sql.prompt.version` (FR20). The reply goes through SQL extraction and the Read-Only Validator, and the response is the FR18 shape with `examples_used: []`. Validator-rejected SQL returns 422 `SQL_REJECTED` with `details.rejected_sql` and `reasons`. Nothing is saved.

**Dependencies:** 4.3.

**Acceptance Criteria:**

**Given** MockWebServer returning a SQL reply wrapped in markdown fences
**When** I POST a valid prompt
**Then** I get 200 with `sql` (normalized and validated), `dialect`, `source: llm-generated`, `original_prompt`, `examples_used: []`, `warnings`, `safety_note`, `valid: true`, and `prompt_version`

**Given** a blank prompt, more than 1,000 chars, or `dialect` not postgresql
**When** I POST
**Then** I get 400 `VALIDATION_FAILED`

**Given** the model returns `DROP TABLE orders`
**When** I POST
**Then** I get 422 `SQL_REJECTED` with the rejected SQL and reasons, and no retry

**And** no Saved Query is created, and switching `sql.prompt.version` changes the template without Java changes

### Story 6.2: Clear LLM failure handling

As a developer,
I want each LLM problem to give me a specific, honest message,
So that I know whether to retry, wait, or just save manually.

**Implements:** FR19 mapping in `LlmClient`:
- timeout, unreachable, provider 5xx, or 503 "loading" → 503 `LLM_UNAVAILABLE`, with `Retry-After` (from `estimated_time` when present) and the manual-save message;
- 429 or quota → 503 `LLM_QUOTA_EXCEEDED`;
- missing token, 401, or 403 → 503 `LLM_NOT_CONFIGURED`.

The service starts and CRUD works without a token. No provider body or stack trace leaks.

**Dependencies:** 6.1.

**Acceptance Criteria:**

**Given** MockWebServer simulating a > 5 s delay, a 500, a 503 with `estimated_time: 20`, a 429, a 401, and no configured token
**When** I POST to `/generate`
**Then** I get, respectively, `LLM_UNAVAILABLE`, `LLM_UNAVAILABLE`, `LLM_UNAVAILABLE` with Retry-After 20, `LLM_QUOTA_EXCEEDED`, `LLM_NOT_CONFIGURED`, and `LLM_NOT_CONFIGURED`, each with its message

**Given** no `HF_API_TOKEN`
**When** the service starts
**Then** it boots and all CRUD endpoints work

**And** each case makes exactly one outbound LLM call (no retries on these failures)

### Story 6.3: One corrective retry, refusal detection, and deadline guard

As a developer,
I want the service to recover once from a garbled model reply and to tell me plainly when the model refuses,
So that I get SQL when possible and a clear reason when not, always within the time limit.

**Implements:** FR17. A second LLM call happens only after an empty, unparseable, or refusal reply, using the retry template. There are never more than two calls. A request deadline (`sql.generation.deadline`, 13 s) skips call 2 if the remaining time is less than the LLM timeout. A final refusal (detected by heuristic) returns 422 `LLM_REFUSED`. Two malformed replies return 422 `SQL_PARSE_ERROR`. Both include `details.llm_raw_response`.

**Dependencies:** 6.2.

**Acceptance Criteria:**

**Given** reply 1 is prose and reply 2 is valid SQL
**When** I POST
**Then** I get 200 with the SQL, after exactly two calls

**Given** both replies are unparseable, or empty
**When** I POST
**Then** I get 422 `SQL_PARSE_ERROR` with `llm_raw_response`

**Given** the final reply is "I can't help with dropping tables"
**When** I POST
**Then** I get 422 `LLM_REFUSED` with "The model declined to generate SQL for this request"

**Given** call 1 used 9 s of a 13 s deadline (simulated)
**When** a retry would be needed
**Then** call 2 is skipped and the request returns 422 within the deadline

### Story 6.4: Ground generation in team Examples (RAG)

As a developer,
I want generation to use the team's most similar saved queries as examples,
So that the SQL uses our real table names and conventions without me pasting a schema.

**Implements:**
- **Retrieval:** embed the prompt, search `sql_embeddings` (limit 5, `sql.rag.threshold`), and fetch the Indexed Queries (FR14).
- **Prompt building:** inject them as delimited `<example>` data blocks, using intent text (original_nl_prompt or title + description) plus SQL, each truncated to 2,000 chars, with a total cap.
- **No match:** the FR15 Warning.
- **RAG failure:** embedding or Qdrant failure falls back to zero-shot with a RAG-unavailable Warning (FR16).
- **Toggle:** `sql.rag.enabled` turns RAG off for evaluation (FR36).
- **Response:** `examples_used` lists ids and titles.

**Dependencies:** 6.3, 5.5.

**Acceptance Criteria:**

**Given** the seed corpus and a prompt about weekly orders by region (real Qdrant, mocked embedding with deterministic vectors)
**When** I POST
**Then** the LLM request contains up to 5 `<example>` blocks of Indexed Queries only, and `examples_used` lists them

**Given** no point meets the threshold
**When** I POST
**Then** generation runs without examples and warnings include "no similar queries found — verify table names in the generated SQL"

**Given** Qdrant is stopped, or embedding fails
**When** I POST with the LLM up
**Then** I get 200 with SQL and the RAG-unavailable Warning, never a 5xx caused by RAG

**Given** `sql.rag.enabled=false`
**When** I POST
**Then** no embedding or Qdrant call is made and `examples_used` is empty, with no no-similar Warning

**And** deprecated, deleted, and non-read-only queries never appear as examples

### Story 6.5: Observability, health, and gateway timeout

As the sql-service owner,
I want each generation's behavior visible in logs and health without leaking prompts or spending quota,
So that I can debug the demo quickly and the gateway never cuts a valid request short.

**Implements:**
- **Generation log line (NFR7):** prompt version, example count, warnings, validation outcome, per-call latencies, and LLM call count. Prompts and examples are logged by length and ids only (NFR5).
- **Health:** an `llm` indicator in the `dependencies` health group reports configuration and last-call status without live calls.
- **Gateway:** a PR to `api-gateway` sets `response-timeout: 15000` on the sql-service route only, owned and reviewed by the gateway owner.

**Dependencies:** 6.4.

**Acceptance Criteria:**

**Given** any generation
**When** it completes or fails
**Then** one structured log line contains the NFR7 fields and no prompt or example text

**Given** repeated `/actuator/health` polling
**When** observed with MockWebServer
**Then** zero LLM calls are made by health checks, and overall health depends only on the DB

**Given** a mocked LLM delaying the full worst case (≈13 s)
**When** called through the gateway
**Then** the response arrives from sql-service (422 or 503 envelope), not a gateway timeout

### Story 6.6: Evaluation harness for SM-2 and SM-3

As the module owner writing the technical report,
I want a repeatable evaluation of generation quality with and without RAG,
So that I can prove the RAG lift with numbers.

**Implements:**
- **Prompt set:** 15 held-out prompts in `src/test/resources/eval/prompts.json`, audited for no seed paraphrases.
- **Harness:** a profile-gated test or CLI that runs each prompt × {RAG on, RAG off} × 3 against the real configured models. It executes each output in a read-only transaction against `seed/schema.sql` (Testcontainers) and writes the executable rate per configuration to `target/eval-report.md`.
- **Scope:** excluded from normal CI.

**Dependencies:** 6.4.

**Acceptance Criteria:**

**Given** valid HF credentials and the `eval` profile
**When** the harness runs
**Then** it produces a report with per-prompt results, executable rate for RAG on and off averaged over 3 runs, and the difference in percentage points

**Given** normal `mvn verify` (CI)
**When** it runs
**Then** the harness is skipped

**And** the prompt file documents its held-out audit, so no prompt paraphrases a seed query (SM-C2)

---

## Epic 7: Generate in the Browser (Angular)

Developers generate SQL in DevAssist Hub, see Warnings, the Safety Note, and the Examples used, and save the result through the short prefilled form. Every generation failure leads to manual save.

### Story 7.1: Generate screen

As a developer at 11pm,
I want to type my request and see the SQL, its warnings, and which team queries informed it,
So that I can judge and copy the draft in seconds.

**Implements:** The Generate screen (FR29, display part). It has a prompt box with a 1,000-character counter and a personal-data notice. Results show the SQL with one-click copy, Warnings, the Safety Note, and "Examples used" linking to each Saved Query's detail. A loading state is shown during generation.

**Dependencies:** 6.4, 3.3.

**Acceptance Criteria:**

**Given** I submit a prompt
**When** generation succeeds
**Then** I see the SQL, any Warnings, the Safety Note, and Examples used as links

**Given** a "no similar queries" Warning
**When** shown
**Then** it is visually distinct from the SQL and readable

**And** the personal-data notice is always visible near the prompt box, and the submit button is disabled while generating

### Story 7.2: Save generated SQL through the short form

As a developer,
I want to save a generated query by typing just a title and description,
So that good drafts join the library and improve future generations.

**Implements:** A Save action on the Generate screen opens the Story 3.4 form prefilled with the SQL, `original_nl_prompt`, and `is_llm_generated = true`. Only title and description are required, and optional fields are collapsed. After saving, a confirmation shows whether the query was indexed, using the indexed flag and Warnings.

**Dependencies:** 7.1, 3.4, 5.3.

**Acceptance Criteria:**

**Given** a successful generation
**When** I click Save and enter a title and description
**Then** the query is created with is_llm_generated = true and original_nl_prompt set, and I see it was added to team examples

**Given** indexing failed (Warning in the 201)
**When** saved
**Then** I see that it was saved but not yet indexed

### Story 7.3: Generation error states with a manual-save path

As a developer,
I want every generation failure explained, with a way to save my own SQL instead,
So that the tool never leaves me stuck when the LLM is down.

**Implements:** FR30, generation part:
- **503 codes:** `LLM_UNAVAILABLE` (with retry hint from Retry-After), `LLM_QUOTA_EXCEEDED`, and `LLM_NOT_CONFIGURED` each show their message;
- **422 codes:** `SQL_REJECTED` (showing reasons), `LLM_REFUSED`, and `SQL_PARSE_ERROR` each show their message;
- **Gateway timeout or network failure:** treated as LLM unavailable.

Every 503 and timeout case offers "Save a query manually", which opens the manual form with the prompt prefilled in notes.

**Dependencies:** 7.2.

**Acceptance Criteria:**

**Given** the backend returns each 503 code, or the gateway times out
**When** the Generate screen handles it
**Then** the specific message is shown with a "Save a query manually" action

**Given** a 422 `SQL_REJECTED`
**When** shown
**Then** the rejected SQL and each reason are displayed

**Given** a 422 `LLM_REFUSED` or `SQL_PARSE_ERROR`
**When** shown
**Then** the message suggests rephrasing

---

## Epic 8: Demo Readiness and Could-Haves

Make the live demo reliable, with an end-to-end check, a shared-token burst test, warm-up, the Week-6 go/no-go and Plan B switch, and a scripted RAG-lift test. Then add the optional features if time allows.

### Story 8.1: End-to-end UJ-1 check through gateway and Angular

As the team,
I want proof that the full path works (browser → gateway → sql-service → Qdrant/HF) at least three days before the demo,
So that integration surprises don't happen on stage.

**Implements:** A documented E2E run on `docker compose up` (all services):
- an automated API-level script through the gateway: generate → save → search → copy/usage → validate;
- a checklist for the UJ-1 UI flow in Angular;
- results recorded in `docs/sql-service-e2e.md` with the date.

**Dependencies:** 7.3, 4.5.

**Acceptance Criteria:**

**Given** the full stack on docker compose
**When** the E2E script runs through the gateway
**Then** every step succeeds, and the UJ-1 UI checklist passes

**And** the run is recorded with a date at least 3 days before the demo (NFR8)

### Story 8.2: Demo hardening — warm-up, burst test, Plan B switch

As the demo presenter,
I want warm models, a verified quota margin, and a one-line switch to Plan B,
So that the demo survives cold starts, shared quota, and slow free-tier models.

**Implements:**
- **Warm-up:** `scripts/warmup.sh` calls the generation and embedding models.
- **Burst test (SM-5):** 15 generate requests with the whole team's services active on the shared token, reporting p95 and failures.
- **Plan B:** a `planb` Spring profile setting the LLM timeout to 8 s and the deadline to 20 s, with a gateway route value of 22 s documented.
- **Demo profile:** INFO logging, plus a config property to point the HF base URL at a dead host for the LLM-down scenario.
- **Runbook and go/no-go:** a runbook in `docs/` and the Week-6 go/no-go decision recorded (OQ-1).

**Dependencies:** 8.1.

**Acceptance Criteria:**

**Given** the warm-up script
**When** run on demo morning
**Then** both models respond and the script reports their latencies

**Given** the burst test 24 h before the demo
**When** run with all team services active
**Then** it reports 0 quota failures and p95 within the active budget, or the go/no-go triggers Plan B

**Given** the `planb` profile
**When** active
**Then** the LLM timeout is 8 s, the deadline 20 s, and the 22 s gateway setting is documented

**Given** the dead-host property
**When** set in the demo profile
**Then** generation returns `LLM_UNAVAILABLE` (not `LLM_NOT_CONFIGURED`)

### Story 8.3: Scripted RAG-lift test (UJ-3)

As the demo presenter,
I want the "save, then ask again" moment scripted and tested against the real models,
So that the RAG improvement shows reliably in front of the instructor.

**Implements:** A fixture pair (an off-domain first prompt and its follow-up), a corrected SQL to save, and a profile-gated test running the sequence against the real models. It asserts that the first response carries the no-similar Warning and the second lists the saved query in `examples_used` and uses its table names. The similarity threshold (OQ-7) is tuned until it passes reliably three times in a row.

**Dependencies:** 8.2, 6.6.

**Acceptance Criteria:**

**Given** the fixture pair and real models
**When** the test runs three times
**Then** each run shows the no-similar Warning first, then the saved query in `examples_used` with its table names in the SQL

**And** the tuned threshold is committed as the default `sql.rag.threshold`

### Story 8.4: Similar queries at save time (Could)

As a developer,
I want to see existing similar queries before saving a new one,
So that I reuse instead of creating a near-duplicate.

**Implements:** FR34:
- **Endpoint:** `POST /api/sql/queries/similar` (body: title, description, sql_content) returns up to 3 Indexed Queries above the threshold.
- **Form:** the save form shows them as links before submit.
- **Failure:** an empty list when RAG is unavailable.

**Dependencies:** 6.4, 7.2.

**Acceptance Criteria:**

**Given** a draft similar to a seed query
**When** I open the save form
**Then** up to 3 similar queries are shown as links

**Given** Qdrant is down
**When** I open the save form
**Then** no suggestions are shown and saving still works

### Story 8.5: Export the library as a .sql file (Could)

As a developer,
I want to export queries to a `.sql` file,
So that the library can live next to code when needed. That answers "why not a shared file?" with "you can have both".

**Implements:** FR35. `GET /api/sql/queries/export` takes the same filters as the list and returns `text/plain` (`.sql` attachment). Each query is written with its title, description, author, and tags as `--` comments followed by its SQL. An Export button is added to the library screen.

**Dependencies:** 1.4, 3.2.

**Acceptance Criteria:**

**Given** filtered results
**When** I click Export
**Then** a `.sql` file downloads containing exactly those queries, each preceded by its comment header

**And** deleted queries are never exported
