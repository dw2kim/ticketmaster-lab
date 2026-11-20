package lab.ticketmaster.booking;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lab.ticketmaster.queue.WaitingRoom;

/**
 * Book tickets: POST /bookings/{eventId} with {"ticketIds": [...]} and header X-User-Id.
 *
 * Which booking code runs is chosen at startup with booking.strategy
 * (naive | row-lock | occ | conditional). See BookingService.
 */
@RestController
public class BookingController {

    public record BookRequest(List<Long> ticketIds) {}

    public record BookResponse(long bookingId) {}

    private final BookingService bookings;
    private final WaitingRoom room;

    public BookingController(Map<String, BookingService> all,
                             @Value("${booking.strategy}") String strategy,
                             WaitingRoom room) {
        this.room = room;
        this.bookings = all.get(strategy);
        if (bookings == null) {
            throw new IllegalArgumentException(
                    "booking.strategy=" + strategy + " is not one of " + all.keySet());
        }
    }

    @PostMapping("/bookings/{eventId}")
    @ResponseStatus(HttpStatus.CREATED)
    public BookResponse book(@PathVariable long eventId,
                             @RequestHeader("X-User-Id") String userId,
                             @RequestBody BookRequest request) {
        room.checkAdmitted(eventId, userId); // step 6: 403 unless let in
        return new BookResponse(bookings.book(eventId, userId, request.ticketIds()));
    }
}
