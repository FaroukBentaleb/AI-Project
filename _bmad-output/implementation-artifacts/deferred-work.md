- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-service-foundation.md`
  summary: Bound `X-User-Id` to the `author varchar(100)` limit (reject with 400 or truncate) when Story 1.2 starts writing `author`.
  evidence: `CallerIdentity` returns the trimmed header unbounded; a header over 100 chars will make inserts fail with a DataIntegrityViolation that the handler maps to 500. Unverified now because nothing writes `author` yet.
