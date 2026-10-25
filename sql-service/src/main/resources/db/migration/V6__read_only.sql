-- Story 4.4: Read-Only Flag. read_only stays NULL for pre-existing rows until the startup backfill
-- (ReadOnlyBackfill) runs the validator over them; every create/edit path sets it.
ALTER TABLE saved_query ADD COLUMN read_only BOOLEAN;
ALTER TABLE saved_query ADD COLUMN read_only_reasons TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX idx_saved_query_read_only ON saved_query (read_only);
