---
title: 'Story 1.1: sql-service foundation — migrations, error contract, identity'
type: 'feature'
created: '2026-10-18'
status: 'done'
baseline_commit: 'a602783285974af6e5b5963c929da9d75fa26bb1'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/prds/prd-sql-service-2026-10-18/addendum.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** sql-service is an empty scaffold: it has no schema, no entity, and an exception handler that leaks `exception.getMessage()` in a non-standard body. Every later Epic 1 story needs a runnable base with versioned migrations, one error contract, snake_case JSON and caller identity.

**Approach:** Add Flyway with a V1 migration for the core Saved Query columns, plus a matching JPA entity and repository. Configure global snake_case Jackson, replace the handler with the `{error, message, field, details}` envelope, and add a request-scoped `X-User-Id` resolver that falls back to `anonymous`. No endpoints yet.

## Boundaries & Constraints

**Always:** Hibernate `ddl-auto: validate` (schema is owned only by Flyway). Persistence tests use Testcontainers PostgreSQL with Flyway, never H2. Error responses never contain stack traces or raw exception messages for 500s. Keep the existing package layout (`controller/ service/ repository/ dto/ entity/ exception/`), port 8084, Eureka, Actuator and springdoc config.

**Never:** No REST endpoints for queries (Story 1.2). No columns from later stories in V1 (indexes → V2, deleted → V3, deprecation → V4, usage → V5, read_only/indexed/seed_key → V6+). No changes to other services, `init.sql`, or the HF config keys.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh boot | empty `sql_db` | Flyway applies V1, Hibernate validate passes, health UP | — |
| Restart | V1 already applied | no migration re-applied | — |
| Bean Validation fail | invalid `@Valid` body | 400 `{error:"VALIDATION_FAILED", message, field:"<snake_case field>", details}` | first field error in `field`, all in `details` |
| Malformed JSON | unparseable body | 400 `VALIDATION_FAILED` | — |
| Unhandled exception | any runtime error | 500 `{error:"INTERNAL_ERROR", message:"An unexpected error occurred"}` | logged server-side with stack trace |
| Identity present | `X-User-Id: alice` | `alice` | trimmed |
| Identity absent/blank | no header, `""`, `"  "` | `anonymous` | — |

## Decisions

- Table `saved_query`, entity `SavedQuery` (matches planning docs; the request's `sql_query`/`SqlQuery` was declined).
- V1 holds core columns only; later columns arrive in each story's own migration (V2–V5).
- `ddl-auto: validate` stays global in `application.yml`; no `prod` profile is added.
- Spec kept whole despite ~2,100 tokens (one cohesive foundation goal).

</frozen-after-approval>

## Code Map

- `sql-service/pom.xml` -- single-line XML; add `flyway-core`, `flyway-database-postgresql` (versions from Boot BOM), `spring-boot-starter-validation`, and test deps `spring-boot-starter-test`, `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`.
- `sql-service/src/main/resources/application.yml` -- already has `ddl-auto: validate`; add Jackson SNAKE_CASE and Flyway settings. Leave `hf.*` keys alone.
- `sql-service/src/main/java/com/devassisthub/sql/exception/GlobalExceptionHandler.java` -- existing `@RestControllerAdvice` catching `Exception` and returning `exception.getMessage()`; replace.
- `sql-service/src/main/java/com/devassisthub/sql/{entity,repository,dto,controller,service}/package-info.java` -- empty packages; new classes go here.
- `infrastructure/postgres/init.sql` -- creates `sql_db`, grants schema `public` to `sql_user`; do not change.
- `docker-compose.yml` (sql-service block) -- datasource env vars, health check on `/actuator/health`; no change needed.
- Toolchain: JDK 21 at `C:\Program Files\Java\jdk-21`; Maven at IntelliJ's bundled `plugins/maven/lib/maven3/bin/mvn`. Neither is on PATH. Docker Desktop must be running for Testcontainers.

## Tasks & Acceptance

**Execution:**
- [x] `sql-service/pom.xml` -- add Flyway, validation, test and Testcontainers deps -- migrations, Bean Validation, real-Postgres tests.
- [x] `sql-service/src/main/resources/db/migration/V1__create_saved_query.sql` -- core columns per addendum §B types: `id bigserial PK`, `title varchar(200) not null`, `description varchar(2000) not null`, `sql_content text not null check (char_length(sql_content) <= 20000)`, `tags text[] not null default '{}'`, `dialect varchar(20) not null default 'postgresql'`, `notes text`, `author varchar(100) not null`, `created_at`/`updated_at timestamptz not null`, `is_llm_generated boolean not null default false`, `original_nl_prompt text`, `version int not null default 0`.
- [x] `entity/SavedQuery.java` -- JPA entity mapping every V1 column; `@Version` on version; `tags` as `List<String>` via `@JdbcTypeCode(SqlTypes.ARRAY)`; timestamps via `@CreationTimestamp`/`@UpdateTimestamp` as `OffsetDateTime`; `created_at` and `author` not updatable.
- [x] `repository/SavedQueryRepository.java` -- `JpaRepository<SavedQuery, Long>`.
- [x] `src/main/resources/application.yml` -- `spring.jackson.property-naming-strategy: SNAKE_CASE`; `spring.flyway.enabled: true`.
- [x] `exception/ErrorResponse.java` -- record `(error, message, field, details)`; nulls serialized (envelope shape stable).
- [x] `exception/ApiException.java` -- base runtime exception carrying `HttpStatus`, `error` code, optional field/details, for later stories (404, 409).
- [x] `exception/GlobalExceptionHandler.java` -- handle `MethodArgumentNotValidException`, `ConstraintViolationException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`/`MissingServletRequestParameterException` → 400 `VALIDATION_FAILED`; `ApiException` → its status; `NoResourceFoundException` → 404 `NOT_FOUND`; `HttpRequestMethodNotSupportedException` → 405; `Exception` → 500 generic, logged.
- [x] `controller/CallerIdentity.java` (or `service/`) -- `@RequestScope` bean resolving `X-User-Id` from the current request; trimmed value or `anonymous`.
- [x] `src/test/.../SavedQueryPersistenceIT.java` -- `@DataJpaTest` + `@AutoConfigureTestDatabase(replace=NONE)` + Testcontainers `postgres:16`: Flyway applies V1, save/load round-trips every field incl. tags array, version increments on update, created_at unchanged.
- [x] `src/test/.../GlobalExceptionHandlerTest.java` -- `@WebMvcTest` with a test-only controller covering each I/O matrix error row; asserts snake_case keys and no `trace`/exception text.
- [x] `src/test/.../CallerIdentityTest.java` -- header present, absent, empty, whitespace.

**Acceptance Criteria:**
- Given an empty `sql_db`, when `docker compose up sql-service` runs, then Flyway applies V1, the service registers with Eureka and `/actuator/health` is UP; a restart applies nothing new.
- Given the test suite, when `mvn verify` runs with Docker available, then all tests pass with no H2 on the classpath.

## Implementation Notes

## Spec Change Log

## Review Triage Log

| # | Source | Finding | Verdict | Evidence / route |
|---|---|---|---|---|
| 1 | blind, edge | Optimistic-lock failure → 500, `CONFLICT` unused | false | No update path exists in this story; 409 mapping belongs to Story 1.5 (Design Notes). Not reachable. |
| 2 | blind | `DataIntegrityViolationException` (CHECK/varchar limits) → 500 | false | No write endpoint exists; Story 1.2 adds `@Size` request validation ahead of the DB. Not reachable. |
| 3 | blind, edge | `X-User-Id` > 100 chars overflows `author varchar(100)` | medium (unverified now) | `CallerIdentity` has no caller yet; becomes reachable when Story 1.2 writes `author`. Reject vs truncate is a 1.2 decision → defer. |
| 4 | blind | `X-User-Id` trusted without gateway check | false | PRD/epics define identity as the `X-User-Id` header with `anonymous` fallback; trust model is by design. |
| 5 | blind, edge | 415/406/`MissingRequestHeader`/`HandlerMethodValidationException`/`ResponseStatusException` fall into 500 | medium | Each implements Spring `ErrorResponse`; the catch-all `Exception` handler turns them into 500, breaking strict HTTP semantics. → patch. |
| 6 | blind, edge | `toSnakeCase` diverges from Jackson SNAKE_CASE (`userID` → `user_i_d`) | low | Real; fix is a direct correction (delegate to Jackson's strategy). → patch. |
| 7 | verif, blind, edge | No tests for `ConstraintViolationException` and `NoResourceFoundException` handlers | medium | Pre-verified gap: deleting either handler fails no test. → patch. |
| 8 | blind | `malformedJson` test only checks keys exist, not null | low | Direct assertion fix. → patch. |
| 9 | blind | `updatedAt` assertion `isAfterOrEqualTo` passes if `@UpdateTimestamp` never fires | low | Direct fix: assert strictly after. → patch. |
| 10 | blind | V1 lacks indexes on author/created_at/tags | false | Frozen intent puts indexes in V2 (Story 1.4). |
| 11 | blind | No `dialect` CHECK, no `DEFAULT now()` on timestamps | low | Unlikely in use (all inserts go through Hibernate); editing V1 would also break the already-migrated local DB checksum. Rejected. |
| 12 | blind | `00-create-users.sh` modified but missing from diff | false | `git diff` is empty; only line endings/stat changed, no content. |
| 13 | blind | Repository has no query methods / no endpoints | false | Frozen intent: "No endpoints yet"; later stories add them. |
| 14 | edge | `ApiException` with null status breaks handler | false | No caller passes null; not reachable. |
| 15 | edge | Null default message renders `title: null` | false | Bean Validation constraints always supply a default message. |
| 16 | edge | `setDialect(null)` / null tag elements | false | No caller yet; Story 1.2 validates input. Not reachable. |
| 17 | verif (other) | `*IT` runs only under `mvn verify` and needs Docker | low | True, but the spec's verification is `mvn verify`; CI setup belongs to Epic 2. Rejected. |

## Design Notes

Error codes: `VALIDATION_FAILED` (400), `NOT_FOUND` (404), `CONFLICT` (409, Story 1.5), `METHOD_NOT_ALLOWED` (405), `INTERNAL_ERROR` (500). `field` uses the snake_case JSON name so clients can match it, e.g. `sqlContent` → `sql_content`.

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- expected: BUILD SUCCESS, all tests green.
- `docker compose up -d --build postgres eureka-server sql-service` then `curl localhost:8084/actuator/health` -- expected: `{"status":"UP"}`; `docker compose restart sql-service` logs "Schema is up to date".
