---
title: 'Story 2.2: Qdrant Testcontainer available to the test suite'
type: 'chore'
created: '2026-10-19'
status: 'done'
baseline_commit: '504d215'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** RAG integration tests (Epic 5) need a real Qdrant, and every `*IT` currently starts its own PostgreSQL container.

**Approach:** Add the Testcontainers `qdrant` module (managed by the 1.21.4 BOM) and an abstract `AbstractIntegrationTest` that starts one PostgreSQL and one Qdrant container per test JVM and exposes their connection properties through `@DynamicPropertySource`. All existing `*IT` classes extend it. A smoke IT lists Qdrant collections over REST.

## Boundaries & Constraints

**Always:** Testcontainers 1.21.4 pin; real PostgreSQL (never H2); pinned image tags; unit tests (no `IT` suffix) start no containers.

**Never:** No Qdrant Java client, no production RAG code or config (Story 5.1); no change to production behavior.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| `mvn verify` | Docker running | one PG + one Qdrant container for the whole failsafe run, all ITs green | container start failure fails the ITs |
| Smoke test | `GET /collections` on mapped REST port | 200, `"status":"ok"`, `collections` array | — |
| Unit tests | surefire phase | no container started | — |

## Decisions

- Singleton-container pattern (static block start, no `@Testcontainers`/`@Container`) so containers live for the whole JVM and survive across Spring contexts; Ryuk cleans up at exit.
- PostgreSQL wired via `spring.datasource.url/username/password` dynamic properties instead of `@ServiceConnection` (one mechanism for both containers; `@ServiceConnection` has no Qdrant support).
- Qdrant image pinned to `qdrant/qdrant:v1.13.2` in tests; compose still uses `latest` (not changed here — bumping compose is outside this story). Constants live on the base class.
- Properties injected: `qdrant.host`, `qdrant.port` (gRPC 6334, matching compose `QDRANT_PORT`), `qdrant.rest-port` (6333). Names are provisional for Story 5.1 to bind.
- Smoke test uses JDK `HttpClient` against REST — no Qdrant client dependency.
- Existing ITs migrated to the base (shared database across IT classes); suite stayed green, so no test relied on an empty DB per class.
- Base lives in `com.devassisthub.sql.support` (test sources only).

</frozen-after-approval>

## Code Map

- `sql-service/pom.xml` -- add `org.testcontainers:qdrant` (test).
- `sql-service/src/test/java/com/devassisthub/sql/support/AbstractIntegrationTest.java` -- new base.
- `sql-service/src/test/java/com/devassisthub/sql/support/QdrantContainerIT.java` -- smoke IT.
- 9 existing `*IT` classes -- extend the base, drop per-class containers.

## Tasks & Acceptance

**Execution:**
- [x] Add qdrant Testcontainers module.
- [x] Create `AbstractIntegrationTest` with shared PG + Qdrant and dynamic properties.
- [x] Migrate existing ITs to the base.
- [x] Add Qdrant smoke IT.
- [x] Run `mvn verify`.

**Acceptance Criteria:**
- Given the integration-test base, when `mvn verify` runs, then PostgreSQL and Qdrant start once and are shared across ITs.
- Given the Qdrant smoke test, when it runs, then it lists collections successfully.
- Unit tests (no `*IT` suffix) start no containers.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21, Docker) -- result: BUILD SUCCESS, 142 surefire + 47 failsafe tests green.

**Manual checks:**
- Risk: ITs now share one database; future ITs must not assume an empty table (use unique data / relative assertions).
