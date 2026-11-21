package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import lab.ticketmaster.search.EventSearch;

/** Step 9: Elasticsearch is only a copy. When it is unreachable, search falls back to Postgres. */
@SpringBootTest(properties = {"search.strategy=elastic", "search.elasticsearch.url=http://localhost:1"})
@Import(TestcontainersConfig.class)
class ElasticDownTest {

    @Autowired EventSearch search;

    @Test
    void searchStillWorksFromPostgres() {
        assertThat(search.elastic("jazz")).extracting(EventSearch.EventSummary::name)
                .containsExactly("Quiet Jazz Night");
    }
}
