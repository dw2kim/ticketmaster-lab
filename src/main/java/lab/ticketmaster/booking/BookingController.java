package lab.ticketmaster.booking;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Book tickets: POST /bookings/{eventId} with {"ticketIds": [...]} and header X-User-Id. */
@RestController
public class BookingController {

    public record BookRequest(List<Long> ticketIds) {}

    public record BookResponse(long bookingId) {}

    private final NaiveBookingService bookings;

    public BookingController(NaiveBookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping("/bookings/{eventId}")
    @ResponseStatus(HttpStatus.CREATED)
    public BookResponse book(@PathVariable long eventId,
                             @RequestHeader("X-User-Id") String userId,
                             @RequestBody BookRequest request) {
        return new BookResponse(bookings.book(eventId, userId, request.ticketIds()));
    }
}
