package lab.ticketmaster.event;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Step 10: PUT /events/{id} {"name":"..."} renames an event (to create changes to sync).
 * No login: this is a lab. See EventUpdates.
 */
@RestController
public class EventUpdateController {

    public record Rename(String name) {}

    private final EventUpdates updates;

    public EventUpdateController(EventUpdates updates) {
        this.updates = updates;
    }

    @PutMapping("/events/{id}")
    public Rename rename(@PathVariable long id, @RequestBody Rename body) {
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is empty");
        }
        updates.rename(id, body.name());
        return body;
    }
}
