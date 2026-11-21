package lab.ticketmaster.search;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step 10: delivers outbox notes to Elasticsearch (search.sync=outbox).
 *
 * Every search.outbox-poll (default 500 ms), in one transaction per batch of 100:
 *   1. SELECT the oldest unsent notes ... FOR UPDATE SKIP LOCKED
 *        FOR UPDATE   nobody else can deliver these notes while we hold them
 *        SKIP LOCKED  another worker (another app instance) skips them and takes the next ones
 *   2. send the CURRENT state of those events to Elasticsearch (ElasticIndexer.upsert)
 *   3. UPDATE outbox SET sent_at = now()
 * If step 2 fails, the transaction rolls back: the notes stay unsent and are tried again.
 *
 * At least once: if the app dies after step 2 but before the commit, the notes are sent again
 * next time. That is safe because a send writes the whole document (idempotent).
 *
 * Trade-off: the transaction stays open during the HTTP call to Elasticsearch (up to the 5 s
 * read timeout). It only locks outbox rows, never events or tickets.
 */
@Component
@ConditionalOnProperty(name = "search.sync", havingValue = "outbox")
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int BATCH = 100;

    record Note(long id, long eventId) {}

    private final JdbcClient db;
    private final ElasticIndexer indexer;
    // A TransactionTemplate, not @Transactional: deliverBatch() is called from this same class,
    // and a call from inside the class skips Spring's proxy (the same trap as @Cacheable, step 7).
    private final TransactionTemplate tx;
    private volatile boolean elasticDown;

    public OutboxWorker(JdbcClient db, ElasticIndexer indexer, PlatformTransactionManager txManager) {
        this.db = db;
        this.indexer = indexer;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${search.outbox-poll:500ms}")
    public void deliver() {
        // keep going while there is a full batch: drains a backlog quickly
        while (deliverBatch() == BATCH) {
            // next batch
        }
    }

    /** Deliver up to one batch. Returns how many notes were delivered (0 if none or on failure). */
    public int deliverBatch() {
        try {
            Integer delivered = tx.execute(status -> {
                List<Note> notes = db.sql("""
                        SELECT id, event_id FROM outbox
                         WHERE sent_at IS NULL
                         ORDER BY id
                         LIMIT :batch
                           FOR UPDATE SKIP LOCKED
                        """)
                        .param("batch", BATCH)
                        .query(Note.class)
                        .list();
                if (notes.isEmpty()) {
                    return 0;
                }
                indexer.upsert(notes.stream().map(Note::eventId).distinct().toList());
                db.sql("UPDATE outbox SET sent_at = now() WHERE id IN (:ids)")
                        .param("ids", notes.stream().map(Note::id).toList())
                        .update();
                return notes.size();
            });
            if (elasticDown && delivered != null && delivered > 0) {
                elasticDown = false;
                log.info("Elasticsearch is back: delivering waiting outbox notes");
            }
            return delivered == null ? 0 : delivered;
        } catch (RuntimeException elasticFailed) {
            if (!elasticDown) { // log once per outage, not every 500 ms
                elasticDown = true;
                log.warn("Elasticsearch unavailable: outbox notes wait and will be retried ({})",
                        elasticFailed.getMessage());
            }
            return 0;
        }
    }
}
