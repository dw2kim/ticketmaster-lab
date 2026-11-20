package lab.ticketmaster.booking;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Step 5: holds kept in Postgres (booking.hold-store=postgres, the default).
 *
 * Holds use "implicit expiry" in Postgres (docs/design/05-deep-dives.md section 2):
 * a seat can be taken if it is available, OR reserved but the hold ran out.
 * Both reserve and confirm are ONE short transaction each. Nothing stays open while the
 * buyer types their card details.
 */
@Service("postgres")
public class PostgresHolds implements Holds {

    private final JdbcClient db;
    private final Duration holdFor;

    public PostgresHolds(JdbcClient db, @Value("${booking.hold-duration}") Duration holdFor) {
        this.db = db;
        this.holdFor = holdFor;
    }

    @Override
    @Transactional
    public Hold reserve(long eventId, String userId, List<Long> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketIds is empty");
        }

        long bookingId = db.sql("""
                INSERT INTO bookings (user_id, status, seat_count)
                VALUES (:userId, 'in_progress', :seats)
                RETURNING id
                """)
                .param("userId", userId)
                .param("seats", ticketIds.size())
                .query(Long.class)
                .single();

        // Same idea as step 4's conditional update: check and write in ONE statement.
        // New: an expired hold counts as free.
        int changed = db.sql("""
                UPDATE tickets
                   SET status = 'reserved',
                       reserved_until = now() + :holdSeconds * interval '1 second',
                       booking_id = :bookingId,
                       version = version + 1
                 WHERE event_id = :eventId
                   AND id IN (:ids)
                   AND (status = 'available'
                        OR (status = 'reserved' AND reserved_until < now()))
                """)
                .param("holdSeconds", holdFor.toSeconds())
                .param("bookingId", bookingId)
                .param("eventId", eventId)
                .param("ids", ticketIds)
                .update();

        if (changed != ticketIds.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "seat already held or booked");
        }

        OffsetDateTime expiresAt = db.sql("SELECT min(reserved_until) FROM tickets WHERE booking_id = :id")
                .param("id", bookingId)
                .query(OffsetDateTime.class)
                .single();
        return new Hold(bookingId, expiresAt);
    }

    /**
     * Pay and turn the hold into a sale. The payment is fake and always succeeds.
     *
     * Safe to call twice (idempotent): a booking that is already confirmed just answers
     * "confirmed" again. Real payment providers retry, so this matters.
     *
     * All seats or none: if even one hold ran out and someone else took the seat, the whole
     * booking expires (a real system would refund the payment).
     */
    @Override
    @Transactional(noRollbackFor = HoldExpiredException.class) // keep the "expired" status
    public Confirmed confirm(long bookingId, String userId) {
        record Booking(String status, int seatCount) {}
        Booking booking = db.sql("""
                SELECT status, seat_count FROM bookings
                 WHERE id = :id AND user_id = :userId
                   FOR UPDATE
                """)
                .param("id", bookingId)
                .param("userId", userId)
                .query(Booking.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such booking"));

        if (booking.status().equals("confirmed")) {
            return new Confirmed(bookingId, "confirmed"); // second call: same answer, nothing changes
        }
        if (!booking.status().equals("in_progress")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "booking is " + booking.status());
        }

        // Lock OUR seats whose hold is still valid. Nobody can take them while we hold the lock.
        // A seat whose hold ran out and was taken by someone else no longer has our booking_id.
        List<Long> stillOurs = db.sql("""
                SELECT id FROM tickets
                 WHERE booking_id = :id
                   AND status = 'reserved'
                   AND reserved_until >= now()
                   FOR UPDATE
                """)
                .param("id", bookingId)
                .query(Long.class)
                .list();

        if (stillOurs.size() != booking.seatCount()) {
            // Too late for at least one seat. Give back any we still hold, expire the booking.
            db.sql("""
                    UPDATE tickets SET status = 'available', reserved_until = NULL, booking_id = NULL
                     WHERE booking_id = :id AND status = 'reserved'
                    """).param("id", bookingId).update();
            db.sql("UPDATE bookings SET status = 'expired' WHERE id = :id").param("id", bookingId).update();
            throw new HoldExpiredException();
        }

        // fake payment: pretend the card was charged here

        db.sql("""
                UPDATE tickets
                   SET status = 'booked', reserved_until = NULL, version = version + 1
                 WHERE id IN (:ids)
                """)
                .param("ids", stillOurs)
                .update();
        db.sql("UPDATE bookings SET status = 'confirmed' WHERE id = :id").param("id", bookingId).update();
        return new Confirmed(bookingId, "confirmed");
    }

    /** Postgres holds are already in the tickets table; the seat map reads them from there. */
    @Override
    public Set<Long> heldNow(long eventId) {
        return Set.of();
    }
}
