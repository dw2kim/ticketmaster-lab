-- Step 5: what is held, what is sold, and which holds already ran out.
SELECT
    count(*) FILTER (WHERE status = 'reserved' AND reserved_until >= now()) AS held_now,
    count(*) FILTER (WHERE status = 'reserved' AND reserved_until <  now()) AS hold_ran_out,
    count(*) FILTER (WHERE status = 'booked')                               AS sold,
    count(*) FILTER (WHERE status = 'available')                            AS free
  FROM tickets
 WHERE event_id = 1;
