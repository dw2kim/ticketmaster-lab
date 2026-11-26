package lab.ticketmaster.search;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * Copies events from Postgres into Elasticsearch.
 *
 * Postgres stays the source of truth. Elasticsearch holds a COPY that we can always throw away
 * and rebuild.
 *   step 9   reindexAll(): copy everything (at startup with search.reindex-on-start=true)
 *   step 10  upsert(ids): copy just these events (called by the OutboxWorker and dual-write)
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
        String mapping = new ClassPathResource("elasticsearch/events-index.json")
                .getContentAsString(StandardCharsets.UTF_8);
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
                    .query(ElasticIndexer::doc)
                    .list();
            if (batch.isEmpty()) {
                break;
            }
            sendBulk(bulkLines(batch, Set.of()));
            total += batch.size();
            lastId = batch.getLast().id();
        }
        // make the new documents searchable right away (normally Elasticsearch does this about every second)
        elastic.post().uri("/events/_refresh").retrieve().toBodilessEntity();
        return total;
    }

    /**
     * Step 10: copy the CURRENT state of these events from Postgres. Sends the whole document,
     * so sending the same event twice gives the same result (idempotent). An id that no longer
     * exists in Postgres is deleted from the copy. Throws if Elasticsearch did not take it all.
     */
    public void upsert(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<Doc> docs = db.sql("""
                SELECT id, name, description, performer_name, venue_name, starts_at
                  FROM events
                 WHERE id IN (:ids)
                """)
                .param("ids", ids)
                .query(ElasticIndexer::doc)
                .list();
        Set<Long> found = docs.stream().map(Doc::id).collect(Collectors.toSet());
        Set<Long> gone = ids.stream().filter(id -> !found.contains(id)).collect(Collectors.toSet());
        sendBulk(bulkLines(docs, gone));
    }

    /**
     * The _bulk API takes "newline-delimited JSON": an action line, then the document, per event.
     *   {"index":{"_index":"events","_id":"5"}}
     *   {"id":5,"name":"Golden Sparrows: ...", ...}
     *   {"delete":{"_index":"events","_id":"7"}}
     */
    private String bulkLines(List<Doc> docs, Set<Long> deletes) {
        StringBuilder body = new StringBuilder();
        for (Doc doc : docs) {
            body.append(json.writeValueAsString(Map.of("index", Map.of("_index", "events", "_id", doc.id()))))
                .append('\n')
                .append(json.writeValueAsString(doc))
                .append('\n');
        }
        for (Long id : deletes) {
            body.append(json.writeValueAsString(Map.of("delete", Map.of("_index", "events", "_id", id))))
                .append('\n');
        }
        return body.toString();
    }

    /**
     * Careful: _bulk answers HTTP 200 even when some documents failed. The body says
     * "errors": true in that case, so we must read it, or we would think the copy is up to date.
     */
    private void sendBulk(String lines) {
        if (lines.isEmpty()) {
            return;
        }
        Map<?, ?> response = elastic.post().uri("/_bulk")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(lines)
                .retrieve()
                .body(Map.class);
        if (response != null && Boolean.TRUE.equals(response.get("errors"))) {
            throw new IllegalStateException("Elasticsearch rejected part of a bulk request");
        }
    }

    private static Doc doc(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Doc(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("performer_name"), rs.getString("venue_name"),
                rs.getObject("starts_at", OffsetDateTime.class));
    }
}
