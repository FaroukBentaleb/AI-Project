# Epic 2 summary: sql-service CI/CD

Delivered in `.github/workflows/sql-service-ci.yml` (path-filtered to `sql-service/**`):
- 2.1 `test`: `mvn verify` (unit + Testcontainers PostgreSQL ITs) on `feature/sql-service-*` pushes and PRs to develop.
- 2.2 Qdrant Testcontainer available to integration tests.
- 2.3 `image-smoke`: builds the Docker image, boots it against PostgreSQL 16 without Eureka/Qdrant/HF, requires `/actuator/health` UP and applied Flyway migrations.
- 2.4 `publish`: on push to `develop`, after test + smoke pass, builds and tags `ghcr.io/<owner>/sql-service:develop` and `:sha-<short-sha>`; push to GHCR gated by `SQL_SERVICE_PUBLISH`.

What you must do on GitHub:
1. Create the `develop` branch (if missing) and make it the PR target.
2. Branch protection on `develop`: require the checks "Test (mvn verify)" and "Image smoke (docker build + /actuator/health)" (they appear after the first run).
3. Once the team agrees on GHCR: Settings > Secrets and variables > Actions > Variables, add `SQL_SERVICE_PUBLISH` = `true`. Ensure Settings > Actions > General allows workflows read/write package permissions if org policy restricts it.
4. After the first push, set the `sql-service` package visibility (Packages > package settings: private/internal/public) and link it to the repo so teammates and the demo host can pull.
