-- Step 8 (optional): make the slow LIKE '%keyword%' search fast WITHOUT changing the query.
-- pg_trgm cuts text into 3-letter pieces ("spa", "par", "arr", ...). A GIN index on those
-- pieces lets Postgres find '%spar%' without reading every row, even for word parts.
-- Add:     docker compose exec -T postgres psql -U lab -d ticketmaster < load/trigram-index.sql
-- Remove:  docker compose exec -T postgres psql -U lab -d ticketmaster -c "DROP INDEX events_name_trgm, events_description_trgm, events_performer_trgm"
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS events_name_trgm        ON events USING GIN (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS events_description_trgm ON events USING GIN (description gin_trgm_ops);
CREATE INDEX IF NOT EXISTS events_performer_trgm   ON events USING GIN (performer_name gin_trgm_ops);
ANALYZE events;
