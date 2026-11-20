-- Step 5: holds. A reserved seat belongs to a buyer until reserved_until.
-- Ticket status: available | reserved | booked
-- A 'reserved' seat whose reserved_until is in the past counts as available again:
-- nobody has to "release" it. The status is computed, not swept.
ALTER TABLE tickets ADD COLUMN reserved_until TIMESTAMPTZ;

-- Booking status: in_progress (holding seats) | confirmed (paid) | expired (hold ran out)
-- Existing rows are 'confirmed' already (instant booking from steps 2-4).
-- How many seats this booking asked for. Confirm sells all of them or none.
ALTER TABLE bookings ADD COLUMN seat_count INT;
