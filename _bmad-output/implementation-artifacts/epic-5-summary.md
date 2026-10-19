# Epic 5 summary: Team Knowledge Index (RAG Corpus)

Read-only, live Saved Queries are embedded into the `sql_embeddings` collection in the shared Qdrant. The collection stays in sync on every mutation, heals itself through Reconciliation, and is never empty thanks to the Seed Corpus. CRUD keeps working when Qdrant or embeddings are down. Code lives in `com.devassisthub.sql.rag` and `com.devassisthub.sql.seed`. Qdrant is reached over REST with `RestClient`, with no Qdrant client dependency.

Final verification: backend `mvn verify` gave BUILD SUCCESS with 359 surefire + 73 failsafe tests, 0 failures (Story 5.5 run). There were no frontend changes in this epic.

## Delivered

- **5.1 Collection bootstrap:** creates `sql_embeddings` (cosine, size `hf.embedding-dimension`, default 384) when missing. It never touches other collections and reports a size mismatch without modifying the collection. When Qdrant is unreachable it retries every 30 s. Adds a `qdrant` health indicator in the `dependencies` group, outside the default health. Compose and tests pin `qdrant/qdrant:v1.13.2`.
- **5.2 Embedding client:** HF feature-extraction for `HF_EMBEDDING_MODEL`, with a 2 s timeout and no retries. It returns a typed "embedding unavailable" result and checks the dimension.
- **5.3 Index on save:** `V7__indexed.sql`. The rule is `shouldBeIndexed = read_only && !deleted && !deprecated`, and every mutation calls `syncIndex`. A failure keeps the DB change, sets `indexed = false`, and adds a Warning to the response.
- **5.4 Reconciliation:** runs on first collection readiness (startup or delayed bootstrap) and on `POST /api/sql/admin/reindex` (202, or 503 `RAG_INDEX_UNAVAILABLE`). It removes orphan points, indexes missed rows, and fixes flags. Runs are coalesced, and a run stops early after 3 consecutive failures.
- **5.5 Seed Corpus:** `V8__seed_key.sql` and `seed/schema.sql` (customers, products, orders with `region`/`total`, order_items, plus deterministic sample data relative to `now()`). Also `seed/seed-queries.json`: 15 queries using one "last week" idiom, UNION ALL and CTE week-over-week variants, window functions, `FILTER (WHERE …)` and `DISTINCT ON`. They are inserted on `ApplicationReadyEvent` through the normal create path as author `seed`, and are idempotent by `seed_key`. Deleted or edited seeds are never re-created or overwritten. Toggled by `sql.seed.enabled` (`SQL_SEED_ENABLED`). Every seed is checked against the validator, and runs under EXPLAIN ANALYZE in a read-only transaction on a throwaway Testcontainers PostgreSQL.

## Deferred / risks

- Embedding model and dimension remain open (OQ-1/OQ-3). Changing them requires dropping the collection and reindexing.
- Seeding is synchronous on the ready event. During an embedding outage with Qdrant up, it can spend up to about 2 s per seed (about 30 s) before Reconciliation takes over. The service is already serving during that time.
- Seeds are inserted once per database. Updating the content of a shipped seed needs a new `seed_key`, or a manual delete of the row plus a hard reset.
- Reconciliation keeps point ids in memory, which is fine for the small corpus and is a documented scaling limit.
- The evaluation harness (addendum §H) and OQ-7 threshold tuning on the Seed Corpus belong to Epic 6/7.
