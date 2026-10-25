---
title: 'Story 1.7: Deprecate and restore a Saved Query'
type: 'feature'
created: '2026-10-19'
status: 'done'
baseline_commit: '504d215'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A query that is no longer recommended can only be deleted or left as-is; readers six months later (UJ-2) can't tell it's obsolete or what replaced it.

**Approach:** Add `PUT` and `DELETE /api/sql/queries/{id}/deprecation` (FR7, core) backed by `V4__deprecation.sql` (`deprecated boolean NOT NULL DEFAULT false`, `deprecated_reason text`, `deprecated_at TIMESTAMPTZ`, `replaced_by bigint` self-FK). Each mutation is one service method (`deprecate`, `restore`) so Epic 5.3 index sync can attach there. The response exposes the four fields; the list gains a `deprecated=true|false` filter.

## Boundaries & Constraints

**Always:** Story 1.1 error envelope; any caller may deprecate/restore any query; deleted rows are invisible (404) on both endpoints; deprecated rows remain visible in GET, list and search, and stay editable via PATCH/PUT (which never touch deprecation fields).

**Never:** No indexing logic (Epic 5); no edits to V1–V3; no change to 1.2–1.6 behavior except the added response fields and filter.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Deprecate | `PUT /{id}/deprecation` `{"reason":"r","replaced_by":B}` on live row | 200, body shows `deprecated=true`, reason, `deprecated_at`, `replaced_by=B` | — |
| Deprecate, no replacement | `{"reason":"r"}` | 200, `replaced_by=null` | — |
| Re-deprecate | PUT on deprecated row with new reason/replacement | 200; reason and replaced_by updated; original `deprecated_at` kept | — |
| Restore | `DELETE /{id}/deprecation` on deprecated row | 200; deprecated=false, reason/at/replaced_by null | — |
| Restore idempotent | DELETE on non-deprecated row | 200; nothing changes (no version bump) | — |
| Missing/blank reason | `{}`, `{"reason":"  "}`, reason > 2000 chars, no body/malformed JSON | — | 400 `VALIDATION_FAILED`, `field`=`reason` (body errors: per handler) |
| Bad replacement | replaced_by unknown, soft-deleted, or = id | — | 400 `VALIDATION_FAILED`, `field`=`replaced_by`; row unchanged |
| Unknown / deleted target | PUT or DELETE on unknown or deleted id | — | 404 `NOT_FOUND` |
| Bad id | `/abc/deprecation`, overflow | — | 400 `VALIDATION_FAILED`, `field`=`id` |
| List | no filter | deprecated rows included | — |
| Filter | `deprecated=true` / `false`, combined with others | only matching rows; totals correct | `deprecated=yes` → 400 `field`=`deprecated` |

## Decisions

- No `version` in the deprecation body (same as DELETE in 1.6): these are idempotent state flips; concurrent edits still race-safe via `@Version` because the UPDATE bumps it.
- Re-deprecating keeps the original `deprecated_at` (when it was first withdrawn); only reason and replaced_by change. Omitting `replaced_by` on re-deprecate clears it (PUT = full state).
- Restore on a non-deprecated row performs no write, so `version`/`updated_at` don't move ("nothing changes").
- Restore clears reason, at and replaced_by (no residue on a live query).
- A replacement may itself be deprecated; cycles (A→B, B→A) are allowed. Only unknown, deleted, or self are rejected, per the AC.
- `replaced_by` mapped as a plain `Long` column (not `@ManyToOne`) to keep the entity flat; the FK (`ON DELETE SET NULL`) is enforced in the DB. Hard deletes don't happen, so SET NULL is only a safety net.
- Reason: non-blank, max 2000 chars (matches description), stored as given.
- Filter value parsed like `llm_generated` (case-insensitive true/false, blank = no filter). `LibraryFilter` gains a 5th component; existing tests updated to the new constructor.
- No index on `deprecated` (low-cardinality boolean, small table); can be added later.
- Migration-history assertion uses `startsWith("1","2","3","4")`.

</frozen-after-approval>

## Code Map

- `sql-service/src/main/resources/db/migration/V4__deprecation.sql` -- new.
- `.../entity/SavedQuery.java` -- fields, `markDeprecated`, `clearDeprecation`.
- `.../dto/DeprecateSavedQueryRequest.java` -- new body record.
- `.../dto/SavedQueryResponse.java` -- four new fields.
- `.../service/LibraryFilter.java`, `.../repository/SavedQuerySearchImpl.java` -- `deprecated` filter.
- `.../service/SavedQueryService.java` -- `deprecate`, `restore`.
- `.../controller/SavedQueryController.java` -- endpoints + filter param.
- Tests: `SavedQueryServiceTest`, `LibraryFilterTest`, `SavedQueryControllerTest` (constructor), `SavedQueryDeprecationControllerTest` (WebMvc), `SavedQueryDeprecationIT` (Testcontainers `postgres:16`).

## Tasks & Acceptance

**Execution:**
- [x] V4 migration.
- [x] Entity fields and mutators; response fields.
- [x] Request DTO; service `deprecate`/`restore`; controller endpoints.
- [x] `deprecated` filter in LibraryFilter, controller, native search.
- [x] Unit tests: service (all validation branches, idempotency), LibraryFilter, WebMvc controller.
- [x] `SavedQueryDeprecationIT` -- every matrix row against PostgreSQL; V4 columns and FK present.

**Acceptance Criteria:**
- Given a Saved Query, when I deprecate it with a reason and optional replaced_by, then I get 200 and it shows deprecated = true with reason, timestamp and replacement.
- Given no reason, or a replaced_by that is unknown, deleted, or itself, when I deprecate, then I get 400 `VALIDATION_FAILED`.
- Given an already deprecated query or a non-deprecated query being restored, when I repeat the call, then I get 200 (idempotent).
- Deprecated queries still appear in the list, and `deprecated=true|false` filters them.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 173 tests green (136 surefire, 37 failsafe).
