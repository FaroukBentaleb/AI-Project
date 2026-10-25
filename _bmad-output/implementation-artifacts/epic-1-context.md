# Epic 1 Context: Team Library API — save, find, and manage SQL queries

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Give developers a working Team Library API in sql-service. Through the gateway they can save, view, browse, search, edit, soft-delete, deprecate or restore, and record use of Saved Queries. The epic also lays the foundation every later epic uses: versioned schema migrations, global snake_case JSON, one error envelope and handler, and caller identity from `X-User-Id`. After each story the service must still run, and it must not depend on an LLM. Read-only validation (Epic 4) and RAG indexing (Epic 5) attach to these endpoints later. Design endpoints so those hooks are easy to add, but do not build them now.

## Stories

- Story 1.1: Service foundation — migrations, error contract, identity
- Story 1.2: Save and view a Saved Query
- Story 1.3: Browse the Team Library
- Story 1.4: Search and filter the library
- Story 1.5: Edit a Saved Query safely
- Story 1.6: Delete a Saved Query (soft delete)
- Story 1.7: Deprecate and restore a Saved Query
- Story 1.8: Record usage of a Saved Query

## Requirements & Constraints

- **Create:** title, description, and sql_content are required. tags, notes, dialect, is_llm_generated, and original_nl_prompt are optional. Returns 201 with the full object (id, author, created_at, updated_at, version).
- **Length limits:** title ≤ 200, description ≤ 2,000, sql_content ≤ 20,000, at most 10 tags, each tag ≤ 50 chars. A breach returns 400 `VALIDATION_FAILED` naming the field.
- **Author:** taken from `X-User-Id`. If the header is absent or blank, use `anonymous`. Author and created_at never change.
- **View:** an unknown or soft-deleted id returns 404 `NOT_FOUND`.
- **Browse:** shows all Authors' queries. Defaults are page 0, size 20, created_at descending. size is clamped to 100. Responses include total_elements, total_pages, and page.
- **Sort whitelist:** title, created_at, and updated_at, plus use_count and last_used_at from Story 1.8. Any other field returns 400, never 500.
- **Search:** `q` matches title or description, case-insensitive. `tags` matches any listed tag. `author`, `llm_generated`, and `deprecated` (from Story 1.7) are also filters. All filters combine with AND and keep paging intact. The `read_only` filter comes in Epic 4.
- **Edit:** PATCH changes only the supplied fields. PUT is a full replace with the same validation as create. Optimistic locking uses `version`, and a stale version returns 409 `CONFLICT` with nothing changed. Any user may edit any query, and updated_at advances on each edit.
- **Soft delete:** returns 204 and keeps the row with deleted=true and deleted_at set. Deleted rows are excluded from every read and list. Edits, deletes, and usage calls on a deleted row return 404.
- **Deprecate/restore:** deprecate requires a reason and accepts an optional replaced_by. Return 400 if replaced_by is unknown, deleted, or the query itself. Both operations return 200 and are idempotent. Deprecated queries still appear in lists.
- **Usage:** each call returns 204, increments use_count, and sets last_used_at to now. An unknown or deleted id returns 404.
- **Performance:** reads (view, browse, search) must have p95 < 300 ms on a seed-sized dataset. An integration test measures this.
- **Testing:** service-layer tests for success and validation paths. Persistence tests run on real PostgreSQL through Testcontainers with migrations, never H2.

## Technical Decisions

- **Existing scaffold:** Spring Boot 3.3.5, Java 21, Spring MVC, port 8084, Eureka, springdoc, Actuator. Packages: controller, service, repository, dto, entity, exception.
- **Migrations:** Flyway (flyway-core + flyway-database-postgresql) with `ddl-auto: validate`. The schema grows one migration per story:
  - V1: core columns id, title, description, sql_content, tags `text[]`, dialect, notes, author, created_at, updated_at, is_llm_generated, original_nl_prompt, version.
  - V2 (1.4): indexes on author and created_at, and a GIN index on tags.
  - V3 (1.6): deleted, deleted_at, and an index on deleted.
  - V4 (1.7): deprecated, deprecated_reason, deprecated_at, and a replaced_by self-FK.
  - V5 (1.8): use_count (default 0) and last_used_at.
  - Later epics take V6 onward (read_only, indexed, seed_key).
- **Database:** `sql_db` is created by `infrastructure/postgres/init.sql`. Restarting the service must not reapply migrations.
- **JSON:** Jackson uses SNAKE_CASE globally.
- **Errors:** a single `@ControllerAdvice` returns the envelope `{error, message, field?, details?}`. Bean Validation failures return 400 `VALIDATION_FAILED`. Unhandled errors return 500 with a generic message. Stack traces are never returned. HTTP semantics are strict.
- **Identity:** a request-scoped resolver reads `X-User-Id`.
- **Endpoints:**
  - `POST` and `GET /api/sql/queries`
  - `GET`, `PATCH`, `PUT`, and `DELETE /api/sql/queries/{id}`
  - `PUT` and `DELETE /api/sql/queries/{id}/deprecation`
  - `POST /api/sql/queries/{id}/usage`
- **Future hooks:** later epics will call one `syncIndex(q)` from every mutation, and one validator from create and from sql_content changes. Keep each mutation in one clear service method so these hooks can be added later.

## Cross-Story Dependencies

- Story order:
  - 1.1 → 1.2.
  - 1.2 → 1.3 → 1.4.
  - 1.2 → 1.5.
  - 1.6 needs 1.4 and 1.5.
  - 1.7 and 1.8 need 1.6.
- Epic 2 (CI) can start after 1.1 because it needs the Testcontainers test.
- Epic 3 (Angular library) depends on 1.2, 1.4, 1.5, 1.6, 1.7, and 1.8.
- Epic 4.4 adds read_only and re-validation to create and edit.
- Epic 5.3 adds synchronous indexing to create, edit, delete, deprecate, and restore.
