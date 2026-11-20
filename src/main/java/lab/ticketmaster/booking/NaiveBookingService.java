package lab.ticketmaster.booking;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The naive booking from step 1: look, decide, write.
 *
 * It is correct when buyers come one at a time. It is WRONG when they come at the same
 * moment: between the SELECT (look) and the UPDATE (write) there is a gap, and another
 * buyer can look at the same seat in that gap. Step 3 proves it; step 4 fixes it.
 *
 * Deliberately no @Transactional and no locks.
 */
@Service("naive")
public class NaiveBookingService implements BookingService {

    private final JdbcClient db;

    public NaiveBookingService(JdbcClient db) {
        this.db = db;
    }

    @Override
    public long book(long eventId, String userId, List<Long> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketIds is empty");
        }

        // 1. look: are all these tickets for this event and still available?
        List<String> statuses = db.sql("""
                SELECT status FROM tickets
                 WHERE event_id = :eventId AND id IN (:ids)
                """)
                .param("eventId", eventId)
                .param("ids", ticketIds)
                .query(String.class)
                .list();

        if (statuses.size() != ticketIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown ticket for this event");
        }

        // 2. decide
        if (!statuses.stream().allMatch("available"::equals)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ticket already booked");
        }

        // ---- the gap: another buyer can run step 1 right now and also see "available" ----

        // 3. write: create the booking, then mark the tickets as ours.
        long bookingId = db.sql("""
                INSERT INTO bookings (user_id, status) VALUES (:userId, 'confirmed')
                RETURNING id
                """)
                .param("userId", userId)
                .query(Long.class)
                .single();

        db.sql("""
                UPDATE tickets
                   SET status = 'booked', booking_id = :bookingId
                 WHERE id IN (:ids)
                """)
                .param("bookingId", bookingId)
                .param("ids", ticketIds)
                .update();

        return bookingId;
    }
}
