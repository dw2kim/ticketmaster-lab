-- Step 8: how does Postgres run each search? Look for "Seq Scan" (reads every row) versus
-- "Bitmap Index Scan" (uses an index), and "Execution Time".
-- Run: docker compose exec -T postgres psql -U lab -d ticketmaster < load/explain-search.sql

\echo '=== LIKE %sparrows% ==='
EXPLAIN ANALYZE
SELECT id, name, venue_name, starts_at FROM events
 WHERE name ILIKE '%sparrows%' OR description ILIKE '%sparrows%' OR performer_name ILIKE '%sparrows%'
 ORDER BY starts_at LIMIT 20;

\echo '=== full-text sparrows ==='
EXPLAIN ANALYZE
SELECT id, name, venue_name, starts_at FROM events
 WHERE search @@ websearch_to_tsquery('english', 'sparrows')
 ORDER BY starts_at LIMIT 20;
