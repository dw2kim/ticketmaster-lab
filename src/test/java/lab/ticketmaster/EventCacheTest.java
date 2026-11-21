package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

import lab.ticketmaster.booking.Holds;
import lab.ticketmaster.event.EventController;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Step 7: event details come from Redis after the first read; a stale seat map cannot oversell. */
@SpringBootTest(properties = {"cache.event-details-ttl=PT1H", "cache.seat-map-ttl=PT1H"})
@Import(TestcontainersConfig.class)
class EventCacheTest {

    @Autowired EventController events;
    @Autowired @Qualifier("postgres") Holds holds;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcClient db;

    @BeforeEach
    void reset() {
        db.sql("UPDATE tickets SET status = 'available', booking_id = NULL, reserved_until = NULL").update();
        db.sql("DELETE FROM bookings").update();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void firstReadFillsTheCacheSecondReadUsesIt() {
        assertThat(redis.hasKey("event:1:details")).isFalse();
        events.get(1);
        assertThat(redis.hasKey("event:1:details")).isTrue();
        assertThat(redis.getExpire("event:1:details")).isBetween(3500L, 3600L);

        // Prove the second read does not ask the database: change the row, read again.
        db.sql("UPDATE events SET name = 'Renamed' WHERE id = 1").update();
        assertThat(events.get(1).name()).isEqualTo("Lab Concert"); // still the cached copy
        db.sql("UPDATE events SET name = 'Lab Concert' WHERE id = 1").update();
    }

    @Test
    void staleSeatMapShowsATakenSeatAsFreeButReserveStillSaysNo() {
        events.get(1);                                  // seat map cached: A1 available
        holds.reserve(1, "ana", List.of(1L));           // Ana takes A1 in the database

        assertThat(events.get(1).tickets().getFirst().status()).isEqualTo("available"); // stale
        assertThatThrownBy(() -> holds.reserve(1, "ben", List.of(1L)))                  // but...
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test
    void unknownEventIsNotCached() {
        assertThatThrownBy(() -> events.get(999)).isInstanceOf(ResponseStatusException.class);
        assertThat(redis.hasKey("event:999:details")).isFalse();
    }
}
