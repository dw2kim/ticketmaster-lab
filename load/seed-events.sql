-- Step 8: add 100,000 events so search has something to search.
-- Not a migration: tests stay fast, and you choose when to load it.
-- Run once:   docker compose exec -T postgres psql -U lab -d ticketmaster < load/seed-events.sql
-- Undo:       docker compose exec -T postgres psql -U lab -d ticketmaster -c "DELETE FROM events WHERE id > 2"
--
-- Names are built from word lists, e.g. "Electric Racers: Neon Nights Tour", so some words are
-- common (thousands of events) and some rare (a handful). Events have no tickets.

INSERT INTO events (name, description, venue_name, performer_name, starts_at)
SELECT
    performer || ': ' || show || ' ' || kind,
    performer || ' brings ' || lower(show) || ' to ' || city || '. A ' || genre || ' ' || lower(kind) || ' for everyone.',
    city || ' ' || venue,
    performer,
    timestamptz '2027-01-01' + (n % 730) * interval '1 day' + (n % 5) * interval '2 hours'
FROM (
    SELECT n,
           (ARRAY['Electric','Silver','Midnight','Golden','Crimson','Velvet','Northern','Wild','Lucky','Hollow',
                  'Neon','Iron','Paper','Glass','Quiet','Rapid','Lonely','Bright','Broken','Stellar'])[1 + n % 20]
           || ' ' ||
           (ARRAY['Racers','Foxes','Pilots','Echoes','Rivers','Sparrows','Giants','Kings','Saints','Dreamers',
                  'Wolves','Tigers','Ghosts','Lanterns','Comets','Harbors','Monarchs','Strangers','Sirens','Drifters'])[1 + (n / 20) % 20]
           AS performer,
           (ARRAY['Neon Nights','Summer Lights','Winter Songs','Last Dance','Open Road','City Lights','Long Way Home',
                  'Big Sky','Late Show','First Light','Rewind','Encore','Afterglow','Homecoming','Moonrise'])[1 + (n / 7) % 15] AS show,
           (ARRAY['Tour','Live','Concert','Festival','Show','Night','Session'])[1 + n % 7] AS kind,
           (ARRAY['rock','jazz','pop','folk','comedy','classical','hip hop','electronic','country','blues'])[1 + (n / 3) % 10] AS genre,
           (ARRAY['Boston','Chicago','Denver','Austin','Seattle','Atlanta','Portland','Miami','Toronto','Nashville',
                  'Phoenix','Detroit','Dallas','Montreal','Oakland','Raleigh','Tampa','Omaha','Tucson','Memphis'])[1 + (n / 11) % 20] AS city,
           (ARRAY['Arena','Hall','Theater','Stadium','Club','Pavilion','Garden','Ballroom'])[1 + (n / 13) % 8] AS venue
      FROM generate_series(1, 100000) AS n
) AS words;

ANALYZE events;  -- refresh the planner's statistics after a big insert

SELECT count(*) AS events FROM events;
