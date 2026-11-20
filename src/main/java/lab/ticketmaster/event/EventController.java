package lab.ticketmaster.event;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** View an event: GET /events/{id} -> event details plus every ticket (for the seat map). */
@RestController
public class EventController {

    public record Ticket(long id, String seat, int priceCents, String status) {}

    public record EventView(long id, String name, String description, String venueName,
                            String performerName, OffsetDateTime startsAt, List<Ticket> tickets) {}

    private final JdbcClient db;

    public EventController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping("/events/{id}")
    public EventView get(@PathVariable long id) {
        List<Ticket> tickets = db.sql("""
                SELECT id, seat, price_cents, status
                  FROM tickets
                 WHERE event_id = :id
                 ORDER BY id
                """)
                .param("id", id)
                .query(Ticket.class)
                .list();

        return db.sql("""
                SELECT id, name, description, venue_name, performer_name, starts_at
                  FROM events
                 WHERE id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new EventView(
                        rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                        rs.getString("venue_name"), rs.getString("performer_name"),
                        rs.getObject("starts_at", OffsetDateTime.class), tickets))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such event"));
    }
}
