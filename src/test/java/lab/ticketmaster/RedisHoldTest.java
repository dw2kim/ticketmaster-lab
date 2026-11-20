package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
import lab.ticketmaster.booking.Holds.HoldExpiredException;

/** Step 5b: holds as a Redis lock with TTL. Postgres is still the final guard. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class RedisHoldTest {

    @Autowired @Qualifier("redis") Holds holds;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcClient db;

    @BeforeEach
    void reset() {
        db.sql("UPDATE tickets SET status = 'available', booking_id = NULL, reserved_until = NULL").update();
        db.sql("DELETE FROM bookings").update();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void reserveTakesALockWithTtlAndLeavesTheTableAlone() {
        var hold = holds.reserve(1, "ana", List.of(1L));

        assertThat(redis.opsForValue().get("ticket:1")).isEqualTo(Long.toString(hold.bookingId()));
        assertThat(redis.getExpire("ticket:1")).isBetween(1L, 600L); // Redis will delete it itself
        assertThat(status(1)).isEqualTo("available");               // the DB knows nothing yet
        assertThat(holds.heldNow(1)).containsExactly(1L);           // but the seat map does
    }

    @Test
    void secondBuyerCannotTakeAHeldSeat() {
        holds.reserve(1, "ana", List.of(1L));
        assertThatThrownBy(() -> holds.reserve(1, "ben", List.of(1L)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test
    void confirmSellsInPostgresAndReleasesTheLock() {
        var hold = holds.reserve(1, "ana", List.of(1L));
        assertThat(holds.confirm(hold.bookingId(), "ana").status()).isEqualTo("confirmed");
        assertThat(holds.confirm(hold.bookingId(), "ana").status()).isEqualTo("confirmed"); // twice: same

        assertThat(status(1)).isEqualTo("booked");
        assertThat(redis.hasKey("ticket:1")).isFalse();
        assertThat(holds.heldNow(1)).isEmpty();
    }

    @Test
    void expiredLockMeansTheLateBuyerLoses() {
        var ana = holds.reserve(1, "ana", List.of(1L));
        redis.delete("ticket:1"); // what the TTL does after 10 minutes

        var ben = holds.reserve(1, "ben", List.of(1L));
        holds.confirm(ben.bookingId(), "ben");

        assertThatThrownBy(() -> holds.confirm(ana.bookingId(), "ana"))
                .isInstanceOf(HoldExpiredException.class);
        assertThat(bookingStatus(ana.bookingId())).isEqualTo("expired");
        assertThat(status(1)).isEqualTo("booked");
    }

    @Test
    void lostLockIsCaughtByTheDatabase() {
        // Ana holds A1. Redis loses everything (restart without persistence).
        var ana = holds.reserve(1, "ana", List.of(1L));
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        // Ben now holds and buys A1.
        var ben = holds.reserve(1, "ben", List.of(1L));
        holds.confirm(ben.bookingId(), "ben");

        // Worst case: Ana's app still believes she holds A1 (her lock check passed just
        // before Redis lost it). Simulate that by putting her lock back, then confirm.
        redis.opsForValue().set("ticket:1", Long.toString(ana.bookingId()));
        assertThatThrownBy(() -> holds.confirm(ana.bookingId(), "ana"))
                .isInstanceOf(HoldExpiredException.class);

        // The database's "status = 'available'" check refused the second sale.
        assertThat(count("SELECT count(*) FROM tickets WHERE status = 'booked'")).isEqualTo(1);
        assertThat(bookingStatus(ana.bookingId())).isEqualTo("expired");
    }

    @Test
    void multiSeatHoldIsAllOrNothing() {
        holds.reserve(1, "ben", List.of(2L));
        assertThatThrownBy(() -> holds.reserve(1, "ana", List.of(1L, 2L)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(redis.hasKey("ticket:1")).isFalse(); // Ana's lock on A1 was given back
    }

    @Test
    void twoHundredBuyersReservingAtOnceGetExactlyTwentyHolds() throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger held = new AtomicInteger();
        try (ExecutorService buyers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                long seat = (i % 20) + 1;
                String user = "buyer-" + i;
                buyers.submit(() -> {
                    startGun.await();
                    try {
                        holds.reserve(1, user, List.of(seat));
                        held.incrementAndGet();
                    } catch (ResponseStatusException rejected) {
                        // 409, expected for 180 buyers
                    }
                    return null;
                });
            }
            startGun.countDown();
        }
        assertThat(held.get()).isEqualTo(20);
        assertThat(holds.heldNow(1)).hasSize(20);
        assertThat(count("SELECT count(*) FROM bookings WHERE status = 'in_progress'")).isEqualTo(20);
    }

    private String status(long ticketId) {
        return db.sql("SELECT status FROM tickets WHERE id = :id").param("id", ticketId)
                .query(String.class).single();
    }

    private String bookingStatus(long bookingId) {
        return db.sql("SELECT status FROM bookings WHERE id = :id").param("id", bookingId)
                .query(String.class).single();
    }

    private long count(String sql) {
        return db.sql(sql).query(Long.class).single();
    }
}
