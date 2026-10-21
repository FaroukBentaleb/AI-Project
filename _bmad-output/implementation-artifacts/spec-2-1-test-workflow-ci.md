---
title: 'Story 2.1: Test workflow on feature branches and PRs to develop'
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

**Problem:** Nothing runs the sql-service tests automatically, so broken code can reach `develop`.

**Approach:** Add `.github/workflows/sql-service-ci.yml`, triggered on `push` to `feature/sql-service-*` and on `pull_request` targeting `develop`, path-filtered to `sql-service/**` and the workflow file. One job sets up Temurin 21 with Maven caching and runs `mvn -B verify` in `sql-service/` (surefire unit tests + failsafe `*IT` Testcontainers PostgreSQL tests on the runner's Docker). Reports are uploaded on failure.

## Boundaries & Constraints

**Always:** Keep the Testcontainers 1.21.4 pin in `sql-service/pom.xml`; real PostgreSQL via Testcontainers (never H2); job fails when any test fails.

**Never:** No changes to other services' code or other teams' workflows; no secrets; no image build (Story 2.3) or Qdrant (Story 2.2).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Feature push | push to `feature/sql-service-crud` touching `sql-service/**` | workflow runs `mvn -B verify`, all unit + IT tests | any failure fails the job |
| PR to develop | PR touching `sql-service/**` or the workflow | workflow runs | same |
| Unrelated PR | PR to develop changing only files outside `sql-service/` | workflow does not run | — |
| Other branches | push to `main`, `feature/other-*` | does not run | — |
| Test failure | failing test | job red | surefire + failsafe reports uploaded as artifact |
| Repeat run | same `pom.xml` | Maven deps restored from cache, faster than first run | cache miss just downloads |

## Decisions

- Runner Maven (`mvn`) is used; the repo has no Maven wrapper and adding one is out of scope.
- `actions/setup-java@v4` `cache: maven` keyed on `sql-service/pom.xml` (`cache-dependency-path`) gives the dependency cache.
- `-ntp` added to keep logs readable; `defaults.run.working-directory: sql-service`.
- `permissions: contents: read` (least privilege); `concurrency` per ref with `cancel-in-progress` so superseded pushes stop.
- `timeout-minutes: 30` guards hung container starts.
- Report artifact `sql-service-test-reports` (surefire + failsafe dirs), `if: failure()`, 7-day retention, `if-no-files-found: ignore` (compile failures produce no reports).
- Ubuntu runner Docker is used as-is; Testcontainers 1.21.4 works with it as with local Docker Engine 29.
- Path filter applies to both triggers, so a feature-branch push that only touches docs also skips (consistent with the PR rule).

</frozen-after-approval>

## Code Map

- `.github/workflows/sql-service-ci.yml` -- new workflow (single `test` job).
- `_bmad-output/implementation-artifacts/epic-2-context.md` -- new epic context.

## Tasks & Acceptance

**Execution:**
- [x] Write epic-2-context.md.
- [x] Create workflow with triggers, path filters, Temurin 21 + Maven cache, `mvn -B -ntp verify`.
- [x] Upload surefire/failsafe reports on failure.
- [x] Lint workflow (PyYAML parse + actionlint).
- [x] Run `mvn verify` locally to confirm the suite the workflow runs is green.

**Acceptance Criteria:**
- Given a push to `feature/sql-service-crud`, when the workflow runs, then `mvn verify` executes all tests including Testcontainers PostgreSQL tests, and the job fails if any test fails.
- Given a PR to `develop` changing only files outside `sql-service/`, when opened, then the workflow does not run.
- Surefire and failsafe reports are uploaded as artifacts on failure.
- The Maven dependency cache makes repeat runs faster than the first.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f sql-service/pom.xml verify` (JAVA_HOME=JDK 21, Docker running) -- result: BUILD SUCCESS, 142 surefire + 46 failsafe tests green.
- `python -c "yaml.safe_load(...)"` -- parses; `rhysd/actionlint` (Docker) -- 0 errors.

**Manual checks:**
- GitHub Actions cannot be run locally. After pushing to a `feature/sql-service-*` branch / opening a PR to `develop`, confirm: the run is green, an outside-only PR does not trigger it, and the second run logs a Maven cache hit.
- Risk: `SavedQueryReadPerformanceIT` (NFR2 p95 < 300 ms) may be slower on shared runners; watch first runs.
