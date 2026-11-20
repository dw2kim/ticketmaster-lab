-- Put every seat back on sale, so the rush can run again.
UPDATE tickets SET status = 'available', booking_id = NULL;
DELETE FROM bookings;
