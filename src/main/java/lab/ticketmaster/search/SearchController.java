package lab.ticketmaster.search;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search events: GET /events/search?keyword=...
 *
 * Step 2 used LIKE '%keyword%', which reads every row. Step 8 adds full-text search with a GIN
 * index. Step 9 adds Elasticsearch. Pick one with search.strategy (like | fulltext | elastic).
 * See EventSearch.
 */
@RestController
public class SearchController {

    private final EventSearch search;
    private final String strategy;

    public SearchController(EventSearch search, @Value("${search.strategy}") String strategy) {
        if (!List.of("like", "fulltext", "elastic").contains(strategy)) {
            throw new IllegalArgumentException("search.strategy=" + strategy + " is not like, fulltext or elastic");
        }
        this.search = search;
        this.strategy = strategy;
    }

    @GetMapping("/events/search")
    public List<EventSearch.EventSummary> search(@RequestParam(defaultValue = "") String keyword) {
        return switch (strategy) {
            case "fulltext" -> search.fulltext(keyword);
            case "elastic" -> search.elastic(keyword);
            default -> search.like(keyword);
        };
    }
}
