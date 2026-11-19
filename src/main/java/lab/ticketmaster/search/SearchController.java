package lab.ticketmaster.search;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search events: GET /events/search?keyword=...
 *
 * Naive on purpose: LIKE '%keyword%' scans the whole table. Fine for 2 events,
 * slow for millions. Step 8 makes it fast.
 */
@RestController
public class SearchController {

    public record EventSummary(long id, String name, String venueName, OffsetDateTime startsAt) {}

    private final JdbcClient db;

    public SearchController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping("/events/search")
    public List<EventSummary> search(@RequestParam(defaultValue = "") String keyword) {
        return db.sql("""
                SELECT id, name, venue_name, starts_at
                  FROM events
                 WHERE name ILIKE :pattern
                    OR description ILIKE :pattern
                    OR performer_name ILIKE :pattern
                 ORDER BY starts_at
                 LIMIT 20
                """)
                .param("pattern", "%" + keyword + "%")
                .query((rs, n) -> new EventSummary(
                        rs.getLong("id"), rs.getString("name"), rs.getString("venue_name"),
                        rs.getObject("starts_at", OffsetDateTime.class)))
                .list();
    }
}
