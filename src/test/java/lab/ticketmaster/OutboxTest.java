package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import lab.ticketmaster.event.EventUpdates;
import lab.ticketmaster.search.ElasticIndexer;
import lab.ticketmaster.search.EventSearch;
import lab.ticketmaster.search.EventSearch.EventSummary;

/** Step 10: a change and its outbox note commit together; the worker delivers it. */
@SpringBootTest(properties = {"search.sync=outbox", "search.outbox-poll=200ms"})
@Import(TestcontainersConfig.class)
class OutboxTest {

    @DynamicPropertySource
    static void elasticUrl(DynamicPropertyRegistry registry) {
        ElasticTestContainer.register(registry);
    }

    @Autowired EventUpdates updates;
    @Autowired EventSearch search;
    @Autowired ElasticIndexer indexer;
    @Autowired RestClient elastic;
    @Autowired JdbcClient db;

    @BeforeEach
    void freshCopy() throws Exception {
        db.sql("DELETE FROM events WHERE id > 2").update();
        db.sql("UPDATE events SET name = 'Lab Concert' WHERE id = 1").update();
        db.sql("DELETE FROM outbox").update();
        indexer.reindexAll();
    }

    @Test
    void renameWritesANoteAndTheWorkerDeliversIt() throws Exception {
        updates.rename(1, "Zebra Parade");

        assertThat(count("SELECT count(*) FROM outbox WHERE event_id = 1")).isEqualTo(1);
        // the worker runs every 200 ms; Elasticsearch makes changes searchable about every 1 s
        assertThat(waitForElastic("zebra")).containsExactly("Zebra Parade");
        assertThat(count("SELECT count(*) FROM outbox WHERE sent_at IS NULL")).isZero();
    }

    @Test
    void failedChangeLeavesNoNote() {
        assertThatThrownBy(() -> updates.rename(999, "Ghost Show"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThat(count("SELECT count(*) FROM outbox")).isZero(); // rolled back together
    }

    @Test
    void sendingTheSameEventTwiceIsHarmless() {
        indexer.upsert(List.of(1L));
        indexer.upsert(List.of(1L)); // e.g. the app died before marking the note sent
        elastic.post().uri("/events/_refresh").retrieve().toBodilessEntity();
        Map<?, ?> result = elastic.get().uri("/events/_count").retrieve().body(Map.class);
        assertThat(((Number) result.get("count")).intValue()).isEqualTo(2); // still 2, not 3
    }

    private List<String> waitForElastic(String keyword) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            List<String> names = search.elastic(keyword).stream().map(EventSummary::name).toList();
            if (!names.isEmpty()) {
                return names;
            }
            Thread.sleep(200);
        }
        return List.of();
    }

    private long count(String sql) {
        return db.sql(sql).query(Long.class).single();
    }
}
