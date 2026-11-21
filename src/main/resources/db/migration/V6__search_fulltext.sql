-- Step 8: full-text search (docs/design/05-deep-dives.md section 5).
--
-- A tsvector is the text cut into normalized words ("lexemes"):
--   to_tsvector('english', 'Golden Sparrows: Neon Nights Concert')
--   -> 'concert':5 'golden':1 'neon':3 'night':4 'sparrow':2
-- Postgres keeps it up to date by itself (GENERATED ... STORED), and a GIN index maps every
-- word to the events that contain it (an "inverted index", the same idea Elasticsearch uses).
ALTER TABLE events ADD COLUMN search tsvector GENERATED ALWAYS AS (
    to_tsvector('english',
        coalesce(name, '') || ' ' || coalesce(description, '') || ' ' || coalesce(performer_name, ''))
) STORED;

CREATE INDEX events_search_idx ON events USING GIN (search);
