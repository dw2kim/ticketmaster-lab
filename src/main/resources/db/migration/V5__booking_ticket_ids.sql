-- Step 5b: with Redis holds, the tickets table does not know which seats a booking holds
-- (holds live in Redis, tickets stay 'available' until sold). So the booking remembers them.
ALTER TABLE bookings ADD COLUMN ticket_ids BIGINT[];
