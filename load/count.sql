-- Step 3: after the rush, compare what we promised with what exists.
--   people_told_yes : one booking row per "you got it" answer
--   seats_booked    : real seats marked booked (at most 20)
--   people_cheated  : told yes, but the seat now belongs to someone else

SELECT
    (SELECT count(*) FROM bookings)                                        AS people_told_yes,
    (SELECT count(*) FROM tickets WHERE event_id = 1 AND status = 'booked') AS seats_booked,
    (SELECT count(*) FROM bookings b
      WHERE NOT EXISTS (SELECT 1 FROM tickets t WHERE t.booking_id = b.id)) AS people_cheated;
