ALTER TABLE saved_query ADD COLUMN deprecated boolean NOT NULL DEFAULT false;
ALTER TABLE saved_query ADD COLUMN deprecated_reason text;
ALTER TABLE saved_query ADD COLUMN deprecated_at TIMESTAMPTZ;
ALTER TABLE saved_query ADD COLUMN replaced_by bigint;
ALTER TABLE saved_query ADD CONSTRAINT fk_saved_query_replaced_by
    FOREIGN KEY (replaced_by) REFERENCES saved_query (id) ON DELETE SET NULL;
