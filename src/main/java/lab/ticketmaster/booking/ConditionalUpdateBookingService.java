package lab.ticketmaster.booking;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fix 3: atomic conditional update.
 *
 * No separate look at all. One UPDATE says "mark these seats booked, but only the ones that
 * are still available", and tells us how many rows it changed. Postgres does the check and
 * the write together, holding the row lock only for that one statement, so there is no gap.
 * If fewer rows changed than we asked for, someone else got a seat first: roll back, 409.
 */
@Service("conditional")
public class ConditionalUpdateBookingService implements BookingService {

    private final JdbcClient db;

    public ConditionalUpdateBookingService(JdbcClient db) {
        this.db = db;
    }

    @Override
    @Transactional
    public long book(long eventId, String userId, List<Long> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketIds is empty");
        }

        long bookingId = db.sql("""
                INSERT INTO bookings (user_id, status) VALUES (:userId, 'confirmed')
                RETURNING id
                """)
                .param("userId", userId)
                .query(Long.class)
                .single();

        // check and write in ONE statement
        int changed = db.sql("""
                UPDATE tickets
                   SET status = 'booked', booking_id = :bookingId, version = version + 1
                 WHERE event_id = :eventId
                   AND id IN (:ids)
                   AND status = 'available'
                """)
                .param("bookingId", bookingId)
                .param("eventId", eventId)
                .param("ids", ticketIds)
                .update();

        if (changed != ticketIds.size()) {
            // some seat was taken (or does not exist): undo the booking and the seats we did get
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ticket already booked");
        }
        return bookingId;
    }
}
