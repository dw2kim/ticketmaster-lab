package lab.ticketmaster.event;

import java.time.OffsetDateTime;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lab.ticketmaster.event.EventController.EventDetails;
import lab.ticketmaster.event.EventController.SeatMap;
import lab.ticketmaster.event.EventController.Ticket;

/**
 * Step 7: the two database reads behind GET /events/{id}, cached with @Cacheable.
 *
 * Spring wraps this bean in a proxy. On each call the proxy does what ReadThroughCache did by
 * hand: look in Redis, return the value on a hit; on a miss call the method, store the result
 * with the cache's TTL (CacheConfig), return it.
 *
 * Point 4 - why a separate class? The proxy only sees calls that come from OUTSIDE the bean.
 * If EventController called its own @Cacheable method (this.loadDetails(id)), the call would
 * skip the proxy and never touch the cache, with no error. So the cached methods live here,
 * and EventController calls them through the proxy.
 *
 * sync = true: when a key expires and many requests miss at once, only one of them should run
 * the query while the others wait. With Redis this only works together with the LOCKING cache
 * writer in CacheConfig (cache.lock-on-miss=true; the lock is a Redis key, so it holds across
 * app instances too). sync alone did nothing here: we measured ~57 identical queries per expiry.
 *
 * Exceptions are never cached: an unknown event throws 404 and stores nothing.
 */
@Service
public class EventReads {

    private final JdbcClient db;

    public EventReads(JdbcClient db) {
        this.db = db;
    }

    @Cacheable(cacheNames = "event-details", key = "#id", sync = true,
               condition = "@cacheSettings.detailsCached()")
    public EventDetails details(long id) {
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

    @Cacheable(cacheNames = "seat-map", key = "#id", sync = true,
               condition = "@cacheSettings.seatMapCached()")
    public SeatMap seats(long id) {
        return new SeatMap(db.sql("""
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
                .list());
    }
}
