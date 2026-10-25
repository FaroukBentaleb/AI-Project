# Epic 2 Context: CI/CD Pipeline

<!-- Compiled from planning artifacts (epics-sql-service.md, Epic 2). Edit freely. -->

## Goal

Protect every later sql-service story with automation: each push to a `feature/sql-service-*` branch and each PR to `develop` runs the full test suite against real containers (Testcontainers), builds the Docker image and proves it boots, and merging to `develop` builds and tags the image. Included at the user's explicit request (CI/CD requirements, NFR8 automation).

## Stories

- Story 2.1: Test workflow on feature branches and PRs to develop
- Story 2.2: Qdrant Testcontainer available to the test suite
- Story 2.3: Build the Docker image and verify the service starts
- Later Epic 2 stories: build and tag the image on merge to `develop`

## Requirements & Constraints

- Workflow `.github/workflows/sql-service-ci.yml`; triggers: push to `feature/sql-service-*`, PR targeting `develop`; path filter `sql-service/**` plus the workflow file.
- Temurin 21, Maven dependency cache, `mvn -B verify` in `sql-service/` (surefire unit tests + failsafe `*IT` Testcontainers tests).
- Surefire/failsafe reports uploaded as artifacts on failure.
- Integration tests use real PostgreSQL (never H2); Qdrant added in 2.2. Unit tests start no containers.
- Image smoke (2.3): build `sql-service/Dockerfile`, PostgreSQL service container, Eureka disabled, poll `/actuator/health` up to 90 s, dump logs on failure.

## Technical Decisions

- Testcontainers pinned to 1.21.4 in `sql-service/pom.xml` (Boot 3.3.5 default 1.19.x is rejected by Docker Engine 29+); CI must keep it.
- GitHub-hosted `ubuntu-latest` runners provide Docker; no secrets needed for test jobs.
- Only sql-service's own workflow is touched; other teams' services and workflows are out of scope.

## Dependencies

- 2.1 needs Story 1.1 (at least one Testcontainers test). 2.2 and 2.3 build on 2.1.
