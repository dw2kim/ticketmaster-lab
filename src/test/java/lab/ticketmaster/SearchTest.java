package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import lab.ticketmaster.search.EventSearch;
import lab.ticketmaster.search.EventSearch.EventSummary;

/** Step 8: LIKE finds word parts; full-text finds whole words and their forms. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SearchTest {

    @Autowired EventSearch search;

    @Test
    void bothFindAWholeWord() {
        assertThat(names(search.like("jazz"))).containsExactly("Quiet Jazz Night");
        assertThat(names(search.fulltext("jazz"))).containsExactly("Quiet Jazz Night");
    }

    @Test
    void onlyLikeFindsAPartOfAWord() {
        assertThat(names(search.like("jaz"))).containsExactly("Quiet Jazz Night");
        assertThat(search.fulltext("jaz")).isEmpty();
    }

    @Test
    void onlyFulltextFindsAnotherFormOfTheWord() {
        // "concerts" is stemmed to "concert"; LIKE looks for the exact letters "concerts"
        assertThat(names(search.fulltext("concerts"))).containsExactly("Lab Concert");
        assertThat(search.like("concerts")).isEmpty();
    }

    @Test
    void fulltextUnderstandsWhatPeopleType() {
        assertThat(names(search.fulltext("quiet night"))).containsExactly("Quiet Jazz Night"); // both words
        assertThat(search.fulltext("jazz -quiet")).isEmpty();                                   // minus excludes
    }

    @Test
    void emptyKeywordShowsUpcomingEvents() {
        assertThat(search.fulltext("")).hasSize(2);
        assertThat(search.like("")).hasSize(2);
    }

    private static List<String> names(List<EventSummary> found) {
        return found.stream().map(EventSummary::name).toList();
    }
}
