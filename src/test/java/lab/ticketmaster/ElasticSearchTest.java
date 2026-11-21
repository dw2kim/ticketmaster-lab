package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import lab.ticketmaster.search.ElasticIndexer;
import lab.ticketmaster.search.EventSearch;
import lab.ticketmaster.search.EventSearch.EventSummary;

/** Step 9: Elasticsearch holds a copy of the events: fuzzy matches, and stale until reindexed. */
@SpringBootTest(properties = "search.strategy=elastic")
@Import(TestcontainersConfig.class)
class ElasticSearchTest {

    static final GenericContainer<?> ELASTIC = new GenericContainer<>("elasticsearch:9.1.5")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        ELASTIC.start();
    }

    @DynamicPropertySource
    static void elasticUrl(DynamicPropertyRegistry registry) {
        registry.add("search.elasticsearch.url",
                () -> "http://" + ELASTIC.getHost() + ":" + ELASTIC.getMappedPort(9200));
    }

    @Autowired EventSearch search;
    @Autowired ElasticIndexer indexer;
    @Autowired JdbcClient db;

    @BeforeEach
    void freshCopy() throws Exception {
        db.sql("DELETE FROM events WHERE id > 2").update();
        assertThat(indexer.reindexAll()).isEqualTo(2);
    }

    @Test
    void findsWholeWordsAndWordForms() {
        assertThat(names(search.elastic("jazz"))).containsExactly("Quiet Jazz Night");
        assertThat(names(search.elastic("concerts"))).containsExactly("Lab Concert");
    }

    @Test
    void forgivesSmallTypos() {
        // neither LIKE nor Postgres full-text finds these
        assertThat(names(search.elastic("jaz"))).containsExactly("Quiet Jazz Night");    // 1 letter missing
        assertThat(names(search.elastic("concrt"))).containsExactly("Lab Concert");      // 1 letter missing
        assertThat(search.fulltext("concrt")).isEmpty();
    }

    @Test
    void theCopyIsStaleUntilReindexed() throws Exception {
        db.sql("""
                INSERT INTO events (name, description, venue_name, performer_name, starts_at)
                VALUES ('Brand New Show', 'Just announced.', 'Big Hall', 'Newcomers', '2027-03-01 20:00+00')
                """).update();

        assertThat(names(search.fulltext("newcomers"))).containsExactly("Brand New Show"); // Postgres: at once
        assertThat(search.elastic("newcomers")).isEmpty();                                // copy: not yet

        indexer.reindexAll();
        assertThat(names(search.elastic("newcomers"))).containsExactly("Brand New Show"); // after reindex
    }

    @Test
    void emptyKeywordShowsUpcomingEvents() {
        assertThat(names(search.elastic(""))).containsExactly("Lab Concert", "Quiet Jazz Night");
    }

    private static List<String> names(List<EventSummary> found) {
        return found.stream().map(EventSummary::name).toList();
    }
}
