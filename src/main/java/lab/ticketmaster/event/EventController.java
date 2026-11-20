package lab.ticketmaster.event;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lab.ticketmaster.booking.HoldStore;

/** View an event: GET /events/{id} -> event details plus every ticket (for the seat map). */
@RestController
public class EventController {

    public record Ticket(long id, String seat, int priceCents, String status) {}

    public record EventView(long id, String name, String description, String venueName,
                            String performerName, OffsetDateTime startsAt, List<Ticket> tickets) {}

    private final JdbcClient db;
    private final HoldStore holds;

    public EventController(JdbcClient db, HoldStore holds) {
        this.db = db;
        this.holds = holds;
    }

    @GetMapping("/events/{id}")
    public EventView get(@PathVariable long id) {
        List<Ticket> tickets = db.sql("""
                SELECT id, seat, price_cents,
                       -- step 5: a hold that ran out shows as available (implicit expiry)
                       CASE WHEN status = 'reserved' AND reserved_until < now()
                            THEN 'available' ELSE status END AS status
                  FROM tickets
                 WHERE event_id = :id
                 ORDER BY id
                """)
                .param("id", id)
                .query(Ticket.class)
                .list();

        // step 5b: seats held in Redis are 'available' in the table; show them as reserved
        Set<Long> heldInRedis = holds.current().heldNow(id);
        if (!heldInRedis.isEmpty()) {
            tickets = tickets.stream()
                    .map(t -> heldInRedis.contains(t.id()) && t.status().equals("available")
                            ? new Ticket(t.id(), t.seat(), t.priceCents(), "reserved") : t)
                    .toList();
        }
        List<Ticket> seatMap = tickets;

        return db.sql("""
                SELECT id, name, description, venue_name, performer_name, starts_at
                  FROM events
                 WHERE id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new EventView(
                        rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                        rs.getString("venue_name"), rs.getString("performer_name"),
                        rs.getObject("starts_at", OffsetDateTime.class), seatMap))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such event"));
    }
}
