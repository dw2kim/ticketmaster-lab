package lab.ticketmaster.search;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Step 8: two ways to search events by keyword. SearchController picks one with
 * search.strategy (like | fulltext).
 *
 *   like      ILIKE '%keyword%' on name, description, performer. Finds any part of a word
 *             ("spar" finds "Sparrows"), but without an index Postgres reads every row.
 *             load/trigram-index.sql adds an index that speeds up this exact query.
 *   fulltext  search @@ websearch_to_tsquery(keyword). Uses the GIN index from V6. Finds whole
 *             words and their forms ("concerts" finds "Concert"), not word parts ("spar").
 */
@Service
public class EventSearch {

    public record EventSummary(long id, String name, String venueName, OffsetDateTime startsAt) {}

    private final JdbcClient db;

    public EventSearch(JdbcClient db) {
        this.db = db;
    }

    public List<EventSummary> like(String keyword) {
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
                .query(EventSearch::summary)
                .list();
    }

    public List<EventSummary> fulltext(String keyword) {
        if (keyword.isBlank()) {
            return upcoming(); // an empty full-text query matches nothing; show upcoming events instead
        }
        // websearch_to_tsquery understands what people type: words, "quoted phrases", -excluded
        return db.sql("""
                SELECT id, name, venue_name, starts_at
                  FROM events
                 WHERE search @@ websearch_to_tsquery('english', :keyword)
                 ORDER BY starts_at
                 LIMIT 20
                """)
                .param("keyword", keyword)
                .query(EventSearch::summary)
                .list();
    }

    private List<EventSummary> upcoming() {
        return db.sql("SELECT id, name, venue_name, starts_at FROM events ORDER BY starts_at LIMIT 20")
                .query(EventSearch::summary)
                .list();
    }

    private static EventSummary summary(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new EventSummary(rs.getLong("id"), rs.getString("name"), rs.getString("venue_name"),
                rs.getObject("starts_at", OffsetDateTime.class));
    }
}
