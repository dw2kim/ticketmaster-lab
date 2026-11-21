package lab.ticketmaster.event;

// import java.time.Duration;          // no longer used: TTLs moved to CacheSettings / CacheConfig
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

// import org.springframework.beans.factory.annotation.Value;   // no longer used: see CacheSettings
// import org.springframework.http.HttpStatus;                  // no longer used: 404 is thrown in EventReads
// import org.springframework.jdbc.core.simple.JdbcClient;      // no longer used: queries moved to EventReads
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
// import org.springframework.web.server.ResponseStatusException; // no longer used: see EventReads

import lab.ticketmaster.booking.HoldStore;

/**
 * View an event: GET /events/{id} -> event details plus every ticket (for the seat map).
 *
 * Step 7: two caches with different lifetimes, as the design suggests:
 *   event-details:{id}   name, venue, performer   rarely change -> long TTL (cache.event-details-ttl)
 *   seat-map:{id}        ticket statuses          change all the time -> short TTL or none
 *                                                 (cache.seat-map-ttl, default 0 = off)
 * The caching itself is Spring's @Cacheable, on EventReads (see why it is a separate class there).
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

    // First version of step 7 (hand-written read-through cache). Replaced by @Cacheable on
    // EventReads, as a real app would do it. Kept as comments so you can compare:
    //
    // private final JdbcClient db;
    // private final ReadThroughCache cache;
    // private final Duration detailsTtl;
    // private final Duration seatsTtl;

    private final HoldStore holds;
    private final EventReads reads;

    public EventController(HoldStore holds, EventReads reads) {
        this.holds = holds;
        this.reads = reads;
    }

    // Old constructor (hand-written cache). TTLs now live in CacheSettings / CacheConfig.
    //
    // public EventController(JdbcClient db, HoldStore holds, ReadThroughCache cache,
    //                        @Value("${cache.event-details-ttl}") Duration detailsTtl,
    //                        @Value("${cache.seat-map-ttl}") Duration seatsTtl) {
    //     this.db = db;
    //     this.holds = holds;
    //     this.cache = cache;
    //     this.detailsTtl = detailsTtl;
    //     this.seatsTtl = seatsTtl;
    // }

    @GetMapping("/events/{id}")
    public EventView get(@PathVariable long id) {
        // Old: the cache steps were written out here.
        // EventDetails e = cache.get("event:" + id + ":details", detailsTtl, EventDetails.class,
        //         () -> loadDetails(id));
        // List<Ticket> tickets = cache.get("event:" + id + ":seats", seatsTtl, SeatMap.class,
        //         () -> new SeatMap(loadSeats(id))).tickets();

        // New: calls go through the @Cacheable proxy of EventReads (a different bean, so the
        // proxy sees them).
        EventDetails e = reads.details(id);
        List<Ticket> tickets = reads.seats(id).tickets();

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

    // Old: loadDetails(id) and loadSeats(id) lived here. They moved to EventReads as
    // details(id) and seats(id), because a @Cacheable method called from inside its own class
    // skips the cache (point 4). The SQL is unchanged.
    //
    // private EventDetails loadDetails(long id) { ... SELECT ... FROM events ... }
    // private List<Ticket> loadSeats(long id)   { ... SELECT ... FROM tickets ... }
}
