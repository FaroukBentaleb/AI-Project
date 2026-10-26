ALTER TABLE review
    ADD COLUMN language VARCHAR(50) NOT NULL DEFAULT 'Other',
    ADD COLUMN relevant_standard TEXT NOT NULL DEFAULT 'No retrieved standard recorded for this review.',
    ADD COLUMN relevance_score DOUBLE PRECISION NOT NULL DEFAULT 0;

ALTER TABLE review
    ALTER COLUMN language DROP DEFAULT,
    ALTER COLUMN relevant_standard DROP DEFAULT,
    ALTER COLUMN relevance_score DROP DEFAULT;
