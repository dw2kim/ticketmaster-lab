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
 * Step 5: two-step booking.
 *   POST /bookings/reserve        {"eventId":1,"ticketIds":[...]}  -> 201 {bookingId, expiresAt}
 *   POST /bookings/{id}/confirm   (fake payment)                   -> 200 {bookingId, status}
 * Both need header X-User-Id.
 */
@RestController
public class HoldController {

    public record ReserveRequest(long eventId, List<Long> ticketIds) {}

    private final HoldService holds;

    public HoldController(HoldService holds) {
        this.holds = holds;
    }

    @PostMapping("/bookings/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldService.Hold reserve(@RequestHeader("X-User-Id") String userId,
                                    @RequestBody ReserveRequest request) {
        return holds.reserve(request.eventId(), userId, request.ticketIds());
    }

    @PostMapping("/bookings/{bookingId}/confirm")
    public HoldService.Confirmed confirm(@PathVariable long bookingId,
                                         @RequestHeader("X-User-Id") String userId) {
        return holds.confirm(bookingId, userId);
    }
}
