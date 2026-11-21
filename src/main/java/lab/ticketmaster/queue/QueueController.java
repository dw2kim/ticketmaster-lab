package lab.ticketmaster.queue;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 6: the waiting room.
 *   POST /events/{id}/queue   join (once; joining again keeps your place)
 *   GET  /events/{id}/queue   where am I?  {admitted, position, estimatedWaitSeconds}
 * Both need header X-User-Id. When admitted is true, go and reserve.
 *
 * Clients poll GET every few seconds. The design pushes updates with SSE instead; polling
 * keeps the lab small, and the server tells clients how long to wait (estimatedWaitSeconds).
 */
@RestController
public class QueueController {

    private final WaitingRoom room;

    public QueueController(WaitingRoom room) {
        this.room = room;
    }

    @PostMapping("/events/{eventId}/queue")
    public WaitingRoom.Spot join(@PathVariable long eventId, @RequestHeader("X-User-Id") String userId) {
        return room.join(eventId, userId);
    }

    @GetMapping("/events/{eventId}/queue")
    public WaitingRoom.Spot spot(@PathVariable long eventId, @RequestHeader("X-User-Id") String userId) {
        return room.spot(eventId, userId);
    }
}
