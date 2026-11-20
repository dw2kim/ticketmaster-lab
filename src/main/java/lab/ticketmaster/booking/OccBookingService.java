package lab.ticketmaster.booking;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fix 2: optimistic concurrency control (OCC).
 *
 * Look without locking, but remember each seat's version. Then write with
 * "WHERE version = what I saw". If someone else wrote in the gap, the version moved on and
 * our UPDATE changes 0 rows. We then throw, the transaction rolls back (our booking row
 * disappears), and the buyer gets 409. Nobody waits; losers fail fast.
 */
@Service("occ")
public class OccBookingService implements BookingService {

    record Seen(long id, String status, int version) {}

    private final JdbcClient db;

    public OccBookingService(JdbcClient db) {
        this.db = db;
    }

    @Override
    @Transactional
    public long book(long eventId, String userId, List<Long> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketIds is empty");
        }

        // 1. look (no lock) and remember versions
        List<Seen> seen = db.sql("""
                SELECT id, status, version FROM tickets
                 WHERE event_id = :eventId AND id IN (:ids)
                """)
                .param("eventId", eventId)
                .param("ids", ticketIds)
                .query(Seen.class)
                .list();

        if (seen.size() != ticketIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown ticket for this event");
        }

        // 2. decide
        if (!seen.stream().allMatch(s -> "available".equals(s.status()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ticket already booked");
        }

        // ---- the gap is still here, but step 3 now notices it ----

        long bookingId = db.sql("""
                INSERT INTO bookings (user_id, status) VALUES (:userId, 'confirmed')
                RETURNING id
                """)
                .param("userId", userId)
                .query(Long.class)
                .single();

        // 3. write, only if nobody changed the seat since we looked
        for (Seen s : seen) {
            int changed = db.sql("""
                    UPDATE tickets
                       SET status = 'booked', booking_id = :bookingId, version = version + 1
                     WHERE id = :id AND version = :version
                    """)
                    .param("bookingId", bookingId)
                    .param("id", s.id())
                    .param("version", s.version())
                    .update();
            if (changed == 0) {
                // someone wrote in the gap: undo everything (rollback) and say no
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ticket changed, try again");
            }
        }
        return bookingId;
    }
}
