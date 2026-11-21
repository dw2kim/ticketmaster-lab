package lab.ticketmaster.search;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

/**
 * Step 9: copy every event from Postgres into Elasticsearch ("bulk reindex").
 *
 * Postgres stays the source of truth. Elasticsearch holds a COPY that we can always throw away
 * and rebuild. That is the only sync in the small version of step 9: events that change after
 * a reindex are missing (or stale) in search until the next reindex. Real systems keep the copy
 * fresh with an outbox table or CDC (docs/design/05-deep-dives.md section 5).
 *
 * Runs at startup when search.reindex-on-start=true.
 */
@Component
public class ElasticIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ElasticIndexer.class);
    private static final int BATCH = 5_000;

    /** One event as Elasticsearch stores it (the "_source" document). */
    public record Doc(long id, String name, String description, String performerName,
                      String venueName, OffsetDateTime startsAt) {}

    private final JdbcClient db;
    private final RestClient elastic;
    private final ObjectMapper json;
    private final boolean reindexOnStart;

    public ElasticIndexer(JdbcClient db, RestClient elastic, ObjectMapper json,
                          @Value("${search.reindex-on-start}") boolean reindexOnStart) {
        this.db = db;
        this.elastic = elastic;
        this.json = json;
        this.reindexOnStart = reindexOnStart;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (reindexOnStart) {
            long started = System.currentTimeMillis();
            int count = reindexAll();
            log.info("reindexed {} events into Elasticsearch in {} ms", count, System.currentTimeMillis() - started);
        }
    }

    /** Throw the old index away, create it again, copy all events. Returns how many were copied. */
    public int reindexAll() throws Exception {
        try {
            elastic.delete().uri("/events").retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound noIndexYet) {
            // first run: nothing to delete
        }
        String mapping = new ClassPathResource("elasticsearch/events-index.json").getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);
        elastic.put().uri("/events").contentType(MediaType.APPLICATION_JSON).body(mapping)
                .retrieve().toBodilessEntity();

        int total = 0;
        long lastId = 0;
        while (true) {
            List<Doc> batch = db.sql("""
                    SELECT id, name, description, performer_name, venue_name, starts_at
                      FROM events
                     WHERE id > :lastId
                     ORDER BY id
                     LIMIT :batch
                    """)
                    .param("lastId", lastId)
                    .param("batch", BATCH)
                    .query((rs, n) -> new Doc(rs.getLong("id"), rs.getString("name"),
                            rs.getString("description"), rs.getString("performer_name"),
                            rs.getString("venue_name"), rs.getObject("starts_at", OffsetDateTime.class)))
                    .list();
            if (batch.isEmpty()) {
                break;
            }
            elastic.post().uri("/_bulk").contentType(MediaType.parseMediaType("application/x-ndjson"))
                    .body(bulkBody(batch)).retrieve().toBodilessEntity();
            total += batch.size();
            lastId = batch.getLast().id();
        }
        // make the new documents searchable right away (normally Elasticsearch does this about every second)
        elastic.post().uri("/events/_refresh").retrieve().toBodilessEntity();
        return total;
    }

    /**
     * The _bulk API takes "newline-delimited JSON": an action line, then the document, per event.
     *   {"index":{"_index":"events","_id":"5"}}
     *   {"id":5,"name":"Golden Sparrows: ...", ...}
     */
    private String bulkBody(List<Doc> batch) {
        StringBuilder body = new StringBuilder();
        for (Doc doc : batch) {
            body.append(json.writeValueAsString(Map.of("index", Map.of("_index", "events", "_id", doc.id()))))
                .append('\n')
                .append(json.writeValueAsString(doc))
                .append('\n');
        }
        return body.toString();
    }
}
