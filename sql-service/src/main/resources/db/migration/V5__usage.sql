ALTER TABLE saved_query ADD COLUMN use_count bigint NOT NULL DEFAULT 0;
ALTER TABLE saved_query ADD COLUMN last_used_at TIMESTAMPTZ;
