-- Story 5.3: whether a RAG Index point currently exists for the query (addendum §B). Existing rows start false;
-- Reconciliation (Story 5.4) indexes the qualifying ones.
ALTER TABLE saved_query ADD COLUMN indexed BOOLEAN NOT NULL DEFAULT FALSE;
