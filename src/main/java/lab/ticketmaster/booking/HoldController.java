package lab.ticketmaster.booking;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two-step booking (step 5):
 *   POST /bookings/reserve        {"eventId":1,"ticketIds":[...]}  -> 201 {bookingId, expiresAt}
 *   POST /bookings/{id}/confirm   (fake payment)                   -> 200 {bookingId, status}
 * Both need header X-User-Id. Where holds live is chosen with booking.hold-store (step 5b).
 */
@RestController
public class HoldController {

    public record ReserveRequest(long eventId, List<Long> ticketIds) {}

    private final HoldStore store;

    public HoldController(HoldStore store) {
        this.store = store;
    }

    @PostMapping("/bookings/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public Holds.Hold reserve(@RequestHeader("X-User-Id") String userId,
                              @RequestBody ReserveRequest request) {
        return store.current().reserve(request.eventId(), userId, request.ticketIds());
    }

    @PostMapping("/bookings/{bookingId}/confirm")
    public Holds.Confirmed confirm(@PathVariable long bookingId,
                                   @RequestHeader("X-User-Id") String userId) {
        return store.current().confirm(bookingId, userId);
    }
}
