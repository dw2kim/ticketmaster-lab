package lab.ticketmaster.search;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Steps 8-9: three ways to search events by keyword. SearchController picks one with
 * search.strategy (like | fulltext | elastic).
 *
 *   like      ILIKE '%keyword%' on name, description, performer. Finds any part of a word
 *             ("spar" finds "Sparrows"), but without an index Postgres reads every row.
 *             load/trigram-index.sql adds an index that speeds up this exact query.
 *   fulltext  search @@ websearch_to_tsquery(keyword). Uses the GIN index from V6. Finds whole
 *             words and their forms ("concerts" finds "Concert"), not word parts ("spar").
 *   elastic   (step 9) Elasticsearch, a COPY of the events (ElasticIndexer). Words and their
 *             forms like fulltext, plus small typos ("Sparows" finds "Sparrows"), best match
 *             first. If Elasticsearch is down, falls back to fulltext.
 */
@Service
public class EventSearch {

    public record EventSummary(long id, String name, String venueName, OffsetDateTime startsAt) {}

    // The parts of an Elasticsearch search response we read: hits.hits[]._source
    record SearchResponse(Hits hits) {}
    record Hits(List<Hit> hits) {}
    record Hit(ElasticIndexer.Doc _source) {}

    private static final Logger log = LoggerFactory.getLogger(EventSearch.class);

    private final JdbcClient db;
    private final RestClient elastic;

    public EventSearch(JdbcClient db, RestClient elastic) {
        this.db = db;
        this.elastic = elastic;
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

    public List<EventSummary> elastic(String keyword) {
        // multi_match: look in several fields; name and performer count double (^2).
        // fuzziness AUTO: allow 1 typo in words of 3-5 letters, 2 in longer words.
        Map<String, Object> query = keyword.isBlank()
                ? Map.of("query", Map.of("match_all", Map.of()),
                         "sort", List.of(Map.of("startsAt", "asc")),
                         "size", 20)
                : Map.of("query", Map.of("multi_match", Map.of(
                                 "query", keyword,
                                 "fields", List.of("name^2", "performerName^2", "description"),
                                 "fuzziness", "AUTO")),
                         "size", 20);
        try {
            SearchResponse response = elastic.post().uri("/events/_search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(query)
                    .retrieve()
                    .body(SearchResponse.class);
            return response.hits().hits().stream()
                    .map(hit -> hit._source())
                    .map(d -> new EventSummary(d.id(), d.name(), d.venueName(), d.startsAt()))
                    .toList();
        } catch (RestClientException elasticDown) {
            // The copy is unavailable; the source of truth is not. Slower, fewer features, still works.
            log.warn("Elasticsearch unavailable, using Postgres full-text: {}", elasticDown.getMessage());
            return fulltext(keyword);
        }
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
