-- Seed data: no admin endpoints (below the line), so events come from here.

INSERT INTO events (id, name, description, venue_name, performer_name, starts_at) VALUES
    (1, 'Lab Concert', 'The one concert everybody wants.', 'Small Hall', 'The Racers', '2026-12-31 20:00+00'),
    (2, 'Quiet Jazz Night', 'Nobody is in a hurry here.', 'Corner Cafe', 'Slow Trio', '2027-01-15 19:00+00');

SELECT setval('events_id_seq', 2);

-- Event 1: exactly 20 seats, A1..A20. This is the contended event.
INSERT INTO tickets (event_id, seat, price_cents)
SELECT 1, 'A' || n, 5000 FROM generate_series(1, 20) AS n;

-- Event 2: 5 seats.
INSERT INTO tickets (event_id, seat, price_cents)
SELECT 2, 'B' || n, 3000 FROM generate_series(1, 5) AS n;
