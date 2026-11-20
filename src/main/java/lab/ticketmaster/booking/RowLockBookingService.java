package lab.ticketmaster.booking;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fix 1: pessimistic row lock.
 *
 * Same look-decide-write as naive, but the look is SELECT ... FOR UPDATE inside a transaction.
 * The first buyer locks the seat rows; every other buyer of those seats WAITS at the SELECT
 * until the first one commits. When they get in, they see "booked" and get 409.
 *
 * ORDER BY id: two buyers locking seats {1,2} and {2,1} must lock in the same order,
 * or they can deadlock.
 */
@Service("row-lock")
public class RowLockBookingService implements BookingService {

    private final JdbcClient db;

    public RowLockBookingService(JdbcClient db) {
        this.db = db;
    }

    @Override
    @Transactional
    public long book(long eventId, String userId, List<Long> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketIds is empty");
        }

        // 1. look AND lock: other buyers of these rows wait here
        List<String> statuses = db.sql("""
                SELECT status FROM tickets
                 WHERE event_id = :eventId AND id IN (:ids)
                 ORDER BY id
                   FOR UPDATE
                """)
                .param("eventId", eventId)
                .param("ids", ticketIds)
                .query(String.class)
                .list();

        if (statuses.size() != ticketIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown ticket for this event");
        }

        // 2. decide: safe now, nobody else can change these rows until we commit
        if (!statuses.stream().allMatch("available"::equals)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ticket already booked");
        }

        // 3. write
        long bookingId = db.sql("""
                INSERT INTO bookings (user_id, status) VALUES (:userId, 'confirmed')
                RETURNING id
                """)
                .param("userId", userId)
                .query(Long.class)
                .single();

        db.sql("""
                UPDATE tickets
                   SET status = 'booked', booking_id = :bookingId, version = version + 1
                 WHERE id IN (:ids)
                """)
                .param("bookingId", bookingId)
                .param("ids", ticketIds)
                .update();

        return bookingId;
        // commit here -> locks released -> the next waiting buyer reads "booked"
    }
}
