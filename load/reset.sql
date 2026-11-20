-- Put every seat back on sale, so the rush can run again.
UPDATE tickets SET status = 'available', booking_id = NULL, reserved_until = NULL;
DELETE FROM bookings;
