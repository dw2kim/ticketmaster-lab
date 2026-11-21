package lab.ticketmaster.event;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lab.ticketmaster.booking.HoldStore;

/**
 * View an event: GET /events/{id} -> event details plus every ticket (for the seat map).
 *
 * Step 7: two caches with different lifetimes, as the design suggests:
 *   event:{id}:details   name, venue, performer   rarely change -> long TTL (cache.event-details-ttl)
 *   event:{id}:seats     ticket statuses          change all the time -> short TTL or none
 *                                                 (cache.seat-map-ttl, default 0 = off)
 * A stale seat map can show a taken seat as free. That costs a buyer a 409, never a double
 * sale: reserve still checks the database (step 4).
 */
@RestController
public class EventController {

    public record Ticket(long id, String seat, int priceCents, String status) {}

    public record EventDetails(long id, String name, String description, String venueName,
                               String performerName, OffsetDateTime startsAt) {}

    public record SeatMap(List<Ticket> tickets) {}

    public record EventView(long id, String name, String description, String venueName,
                            String performerName, OffsetDateTime startsAt, List<Ticket> tickets) {}

    private final JdbcClient db;
    private final HoldStore holds;
    private final ReadThroughCache cache;
    private final Duration detailsTtl;
    private final Duration seatsTtl;

    public EventController(JdbcClient db, HoldStore holds, ReadThroughCache cache,
                           @Value("${cache.event-details-ttl}") Duration detailsTtl,
                           @Value("${cache.seat-map-ttl}") Duration seatsTtl) {
        this.db = db;
        this.holds = holds;
        this.cache = cache;
        this.detailsTtl = detailsTtl;
        this.seatsTtl = seatsTtl;
    }

    @GetMapping("/events/{id}")
    public EventView get(@PathVariable long id) {
        EventDetails e = cache.get("event:" + id + ":details", detailsTtl, EventDetails.class,
                () -> loadDetails(id));
        List<Ticket> tickets = cache.get("event:" + id + ":seats", seatsTtl, SeatMap.class,
                () -> new SeatMap(loadSeats(id))).tickets();

        // step 5b: seats held in Redis are 'available' in the table; show them as reserved.
        // Always live (not cached): it is already a Redis read.
        Set<Long> heldInRedis = holds.current().heldNow(id);
        if (!heldInRedis.isEmpty()) {
            tickets = tickets.stream()
                    .map(t -> heldInRedis.contains(t.id()) && t.status().equals("available")
                            ? new Ticket(t.id(), t.seat(), t.priceCents(), "reserved") : t)
                    .toList();
        }
        return new EventView(e.id(), e.name(), e.description(), e.venueName(),
                e.performerName(), e.startsAt(), tickets);
    }

    private EventDetails loadDetails(long id) {
        return db.sql("""
                SELECT id, name, description, venue_name, performer_name, starts_at
                  FROM events
                 WHERE id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new EventDetails(
                        rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                        rs.getString("venue_name"), rs.getString("performer_name"),
                        rs.getObject("starts_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such event"));
    }

    private List<Ticket> loadSeats(long id) {
        return db.sql("""
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
    }
}
