CREATE INDEX idx_saved_query_author ON saved_query (author);
CREATE INDEX idx_saved_query_created_at ON saved_query (created_at);
CREATE INDEX idx_saved_query_tags ON saved_query USING GIN (tags);
