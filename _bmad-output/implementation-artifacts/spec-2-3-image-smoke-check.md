---
title: 'Story 2.3: Build the Docker image and verify the service starts'
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

**Problem:** CI proves the tests pass but not that the real `sql-service` image builds and boots, so the demoed image can be broken.

**Approach:** Add an `image-smoke` job to `.github/workflows/sql-service-ci.yml`, `needs: test`. It builds `sql-service/Dockerfile`, starts a PostgreSQL 16 service container with `sql_db`, runs the image with Eureka disabled and Qdrant/HF unreachable or empty, polls `/actuator/health` until UP (90 s budget), checks Flyway history, and dumps container logs on failure.

## Boundaries & Constraints

**Always:** Same triggers/path filters as the test job; real PostgreSQL; job fails on timeout, container exit, or no applied migrations.

**Never:** No changes to other services, other workflows, the Dockerfile or `docker-compose.yml`; no secrets; no image push (Story 2.4).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Healthy start | PR to develop, tests green | image builds, health `UP` within 90 s, `flyway_schema_history` has successful rows | — |
| Tests red | `test` job fails | `image-smoke` skipped | — |
| Boot failure | bad config / migration error | job fails (timeout or container exited early) | `docker logs` printed |
| No Qdrant / HF token | `QDRANT_HOST=127.0.0.1`, `QDRANT_PORT=1`, empty `HF_API_TOKEN` | still UP (FR19, FR21) | — |
| Docker build fails | broken Dockerfile/compile | job fails at build step | build log in step output |

## Decisions

- Container runs with `--network host` so it reaches the Postgres service container at `localhost:5432`, the simplest wiring on GitHub runners.
- Eureka disabled through Spring relaxed binding `EUREKA_CLIENT_ENABLED=false`; no app config change needed.
- Qdrant pointed at `127.0.0.1:1` (closed port) and HF vars set empty. Today the service has no Qdrant client, so this only becomes meaningful once FR19/FR21 stories land (as the story states).
- Poll: 45 x 2 s via `curl` grepping `"status":"UP"`; loop also exits early if the container stopped.
- "Flyway migrations applied" checked explicitly by `psql` in the Postgres service container (`count(*) from flyway_schema_history where success > 0`), not only implied by `ddl-auto: validate`.
- Service DB credentials are throwaway literals in the workflow (ephemeral container, not secrets).
- `timeout-minutes: 20`; container removed in an `if: always()` step.
- No Docker layer cache for the build (keep simple; Story 2.4 may add buildx caching).

</frozen-after-approval>

## Code Map

- `.github/workflows/sql-service-ci.yml` -- new `image-smoke` job after `test`.

## Tasks & Acceptance

**Execution:**
- [x] Add `image-smoke` job (build, Postgres service, run with Eureka off + no Qdrant/HF, health poll, Flyway check, logs on failure, cleanup).
- [x] Lint workflow with actionlint.
- [x] Run `mvn verify` locally.
- [x] Reproduce the smoke check locally against a standalone Postgres container.

**Acceptance Criteria:**
- Given a PR to develop, when image-smoke runs, then the image builds and `/actuator/health` reports UP within 90 s with Flyway migrations applied.
- Given the service fails to start, when the job times out, then the job fails and container logs are printed.
- The service starting without Qdrant or an HF token is part of the check.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JDK 21) -- BUILD SUCCESS, failsafe 47 tests green.
- `rhysd/actionlint` (Docker) -- 0 errors.
- `docker build sql-service` + local replay (isolated network, Postgres 16, same env vars) -- health `{"status":"UP"}` after ~10 s, 5 successful Flyway migrations, no Eureka errors.

**Manual checks:**
- The running compose stack was left untouched; local replay used a user network instead of `--network host` (Docker Desktop). Confirm the first GitHub run is green.
