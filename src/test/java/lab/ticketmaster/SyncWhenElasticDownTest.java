package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import lab.ticketmaster.event.EventUpdates;
import lab.ticketmaster.search.OutboxWorker;

/** Step 10: Elasticsearch unreachable. Dual-write loses the change; the outbox keeps it. */
class SyncWhenElasticDownTest {

    @SpringBootTest(properties = {"search.sync=dual-write", "search.elasticsearch.url=http://localhost:1"})
    @Import(TestcontainersConfig.class)
    @Nested
    class DualWrite {
        @Autowired EventUpdates updates;
        @Autowired JdbcClient db;

        @Test
        void postgresChangesButNothingRemembersThatElasticsearchMissedIt() {
            db.sql("DELETE FROM outbox").update();
            updates.rename(1, "Lost Update Show"); // no error for the caller

            assertThat(db.sql("SELECT name FROM events WHERE id = 1").query(String.class).single())
                    .isEqualTo("Lost Update Show");
            assertThat(db.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
            db.sql("UPDATE events SET name = 'Lab Concert' WHERE id = 1").update();
        }
    }

    @SpringBootTest(properties = {"search.sync=outbox", "search.outbox-poll=1h",
                                  "search.elasticsearch.url=http://localhost:1"})
    @Import(TestcontainersConfig.class)
    @Nested
    class Outbox {
        @Autowired EventUpdates updates;
        @Autowired OutboxWorker worker;
        @Autowired JdbcClient db;

        @BeforeEach
        void clean() {
            db.sql("DELETE FROM outbox").update();
        }

        @Test
        void theNoteWaitsUntilElasticsearchIsBack() {
            updates.rename(1, "Waiting Show");

            assertThat(worker.deliverBatch()).isZero(); // Elasticsearch down: nothing delivered...
            assertThat(db.sql("SELECT count(*) FROM outbox WHERE sent_at IS NULL")
                    .query(Long.class).single()).isEqualTo(1); // ...but the note is still there
            db.sql("UPDATE events SET name = 'Lab Concert' WHERE id = 1").update();
        }
    }
}
