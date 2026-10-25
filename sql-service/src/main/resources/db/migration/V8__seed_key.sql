-- Story 5.5 (addendum §B/§F): stable identifier of a Seed Corpus entry; NULL for every user-created query.
-- Unique so a concurrent second instance cannot insert the same seed twice.
ALTER TABLE saved_query ADD COLUMN seed_key VARCHAR(100) UNIQUE;
