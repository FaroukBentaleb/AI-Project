---
title: 'Story 2.4: Build and tag the image on merge to develop'
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

**Problem:** Nothing produces a tagged sql-service image after integration, so the shared environment and demo have no "latest develop" build.

**Approach:** Add `develop` to the workflow's `push` branches (same `sql-service/**` path filter) and a `publish` job, `needs: [test, image-smoke]`, running only on `push` to `refs/heads/develop`. It builds `sql-service/Dockerfile`, tags `ghcr.io/<owner>/sql-service:develop` and `:sha-<7-char sha>`, logs in to GHCR with `GITHUB_TOKEN` and pushes, then writes the tags to the job summary.

## Boundaries & Constraints

**Always:** Publish only after test + image-smoke pass; `packages: write` only on the `publish` job (workflow stays `contents: read`); tags listed in the job summary.

**Never:** No publishing on feature-branch pushes or PRs; no personal tokens/secrets; no changes to other services' workflows, the Dockerfile or compose.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Merge to develop, publish on | push develop, `SQL_SERVICE_PUBLISH=true` | build, tag, login, push both tags; summary lists them | push failure fails job |
| Merge to develop, publish off | variable unset | build + tag only; login/push skipped; summary says "not pushed" | — |
| Tests or smoke red | `test`/`image-smoke` fail | `publish` skipped | — |
| Feature push / PR | `feature/sql-service-*` or PR to develop | `publish` skipped by `if` | — |
| Owner with capitals | `Heythaam` | image path lowercased (`${OWNER,,}`) | — |

## Decisions

- GHCR is an unconfirmed team assumption (epic). The job is implemented fully, but GHCR login and `docker push` are gated by repository variable `if: vars.SQL_SERVICE_PUBLISH == 'true'`, default off. When off, the job still builds and tags so the pipeline is exercised on every develop merge.
- Plain `docker build`/`docker tag`/`docker push` plus `docker/login-action@v3`; no buildx/metadata-action to keep it readable and consistent with 2.3.
- Image name `ghcr.io/<lowercased repository_owner>/sql-service`; short sha = first 7 chars of `github.sha`.
- Image is rebuilt in `publish` rather than shared from `image-smoke` (simpler than artifact hand-off; same Dockerfile and commit).
- Test + smoke jobs now also run on push to develop (needed so `publish` can depend on them); concurrency group is per-ref, so develop runs cancel older develop runs.

</frozen-after-approval>

## Code Map

- `.github/workflows/sql-service-ci.yml` -- `develop` push trigger; new `publish` job.

## Tasks & Acceptance

**Execution:**
- [x] Add `develop` to push branches.
- [x] Add `publish` job (needs test + image-smoke, develop-only `if`, job-scoped `packages: write`, build + two tags, gated login/push, job summary).
- [x] Lint with actionlint and parse with Python yaml.
- [x] Reproduce build + tag commands locally; remove test tags.

**Acceptance Criteria:**
- Given a merge to develop touching `sql-service/**`, when the workflow runs, then test and image-smoke run first and only on success is the image built and tagged `develop` and `sha-<short-sha>`.
- Given feature-branch pushes or PR builds, when the workflow runs, then no image is published.
- The image tags are shown in the job summary.

## Spec Change Log

## Verification

**Commands:**
- `rhysd/actionlint` (Docker) -- 0 errors (fixed one SC2129 style hint by grouping `$GITHUB_OUTPUT` writes).
- `python -c "yaml.safe_load(...)"` -- parses; jobs `test, image-smoke, publish`.
- `docker build -t ghcr.io/heythaam/sql-service:sha-504d215 sql-service && docker tag ... :develop` -- both tags on the same image ID; tags removed afterwards. Nothing pushed.

**Manual checks:**
- First develop merge: confirm `publish` runs and the summary shows "not pushed" until `SQL_SERVICE_PUBLISH=true` is set.
