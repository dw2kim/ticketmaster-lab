package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;

import lab.ticketmaster.event.EventController;

/**
 * Step 7, point 3: when Redis is down, the event page still works (from the database).
 * Its own Spring context (the property below makes it different), so stopping Redis here does
 * not break other tests.
 */
@SpringBootTest(properties = "cache.seat-map-ttl=PT2S")
@Import(TestcontainersConfig.class)
class CacheRedisDownTest {

    @Autowired EventController events;
    @Autowired GenericContainer<?> redisContainer;

    @Test
    void pageStillWorksWhenRedisIsDown() {
        assertThat(events.get(1).name()).isEqualTo("Lab Concert"); // fills the cache
        redisContainer.stop();                                      // Redis goes away

        var page = events.get(1);                                   // CacheErrorHandler -> database
        assertThat(page.name()).isEqualTo("Lab Concert");
        assertThat(page.tickets()).hasSize(20);
    }
}
