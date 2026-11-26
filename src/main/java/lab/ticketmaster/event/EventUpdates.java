package lab.ticketmaster.event;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import lab.ticketmaster.search.ElasticIndexer;

/**
 * Step 10: change an event (rename it) and keep the Elasticsearch copy in sync.
 * How it syncs is chosen with search.sync:
 *
 *   none        step 9: Postgres only. The copy changes at the next reindex.
 *   dual-write  the broken way: commit Postgres, THEN call Elasticsearch. If that call fails,
 *               Postgres has the change, Elasticsearch never gets it, and nothing notices.
 *   outbox      the fix: write the change AND a note in the outbox table in ONE transaction.
 *               OutboxWorker delivers the note later, retrying until Elasticsearch takes it.
 *
 * Out of the lab's usual scope (no admin endpoints): it exists only to create changes to sync.
 */
@Service
public class EventUpdates {

    private static final Logger log = LoggerFactory.getLogger(EventUpdates.class);

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ElasticIndexer indexer;
    private final CacheManager caches;
    private final String sync;

    public EventUpdates(JdbcClient db, PlatformTransactionManager txManager, ElasticIndexer indexer,
                        CacheManager caches, @Value("${search.sync}") String sync) {
        if (!List.of("none", "dual-write", "outbox").contains(sync)) {
            throw new IllegalArgumentException("search.sync=" + sync + " is not none, dual-write or outbox");
        }
        this.db = db;
        this.tx = new TransactionTemplate(txManager);
        this.indexer = indexer;
        this.caches = caches;
        this.sync = sync;
    }

    public void rename(long id, String name) {
        // One short transaction: the change, plus (outbox only) its note.
        tx.executeWithoutResult(status -> {
            int changed = db.sql("UPDATE events SET name = :name WHERE id = :id")
                    .param("name", name).param("id", id).update();
            if (changed == 0) {
                // the exception rolls the transaction back: no change, and no note either
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such event");
            }
            if (sync.equals("outbox")) {
                db.sql("INSERT INTO outbox (event_id) VALUES (:id)").param("id", id).update();
            }
        });

        if (sync.equals("dual-write")) {
            try {
                indexer.upsert(List.of(id)); // the second write, outside the transaction
            } catch (RuntimeException elasticFailed) {
                // Postgres already committed. This change will never reach Elasticsearch.
                log.warn("dual-write: Elasticsearch update LOST for event {}: {}", id, elasticFailed.getMessage());
            }
        }

        // Step 7 caches the event details for an hour: drop the old name.
        Cache details = caches.getCache("event-details");
        try {
            if (details != null) {
                details.evict(id);
            }
        } catch (RuntimeException redisDown) {
            log.warn("could not evict event-details {} from the cache: {}", id, redisDown.getMessage());
        }
    }
}
