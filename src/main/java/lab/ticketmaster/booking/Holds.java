package lab.ticketmaster.booking;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Reserve now, pay within the hold time. Two implementations, chosen with booking.hold-store:
 *   postgres - step 5: tickets.reserved_until, "reserved but expired = free" (implicit expiry)
 *   redis    - step 5b: SET ticket:{id} <bookingId> NX EX <seconds> (distributed lock with TTL)
 *
 * In both, Postgres decides the final sale. Redis only holds; it never records a sale.
 */
public interface Holds {

    record Hold(long bookingId, OffsetDateTime expiresAt) {}

    record Confirmed(long bookingId, String status) {}

    /** Hold the seats for this user. 409 if any seat is booked or held by someone else. */
    Hold reserve(long eventId, String userId, List<Long> ticketIds);

    /** Fake payment, then sell all seats or none. Idempotent. 409 if the hold ran out. */
    Confirmed confirm(long bookingId, String userId);

    /** Ticket ids held right now that the tickets table does not know about (Redis only). */
    Set<Long> heldNow(long eventId);

    /** 409 when the hold ran out (or was lost) before payment. */
    @ResponseStatus(value = HttpStatus.CONFLICT, reason = "hold expired; payment would be refunded")
    class HoldExpiredException extends RuntimeException {}
}
