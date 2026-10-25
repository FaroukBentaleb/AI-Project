ALTER TABLE saved_query ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE saved_query ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX idx_saved_query_deleted ON saved_query (deleted);
