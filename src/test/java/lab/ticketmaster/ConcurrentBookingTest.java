package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import lab.ticketmaster.booking.BookingService;

/**
 * Step 4: 200 buyers at the same moment, 10 per seat, 20 seats.
 * Every fix must keep the invariant: exactly 20 "yes" answers, and every "yes" owns its seat.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ConcurrentBookingTest {

    static final int BUYERS = 200;
    static final int SEATS = 20;

    @Autowired Map<String, BookingService> strategies;
    @Autowired JdbcClient db;

    @BeforeEach
    void resetSeats() {
        db.sql("UPDATE tickets SET status = 'available', booking_id = NULL").update();
        db.sql("DELETE FROM bookings").update();
    }

    @ParameterizedTest
    @ValueSource(strings = {"row-lock", "occ", "conditional"})
    void twoHundredBuyersAtOnceSellExactlyTwentySeats(String strategy) throws Exception {
        BookingService booking = strategies.get(strategy);
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger yes = new AtomicInteger();
        AtomicInteger no = new AtomicInteger();

        try (ExecutorService buyers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < BUYERS; i++) {
                long seat = (i % SEATS) + 1; // 10 buyers fight over each seat
                String user = "buyer-" + i;
                buyers.submit(() -> {
                    startGun.await();
                    try {
                        booking.book(1, user, List.of(seat));
                        yes.incrementAndGet();
                    } catch (Exception rejected) {
                        no.incrementAndGet();
                    }
                    return null;
                });
            }
            startGun.countDown(); // everyone goes at once
        } // waits for all buyers

        long bookings = db.sql("SELECT count(*) FROM bookings").query(Long.class).single();
        long cheated = db.sql("""
                SELECT count(*) FROM bookings b
                 WHERE NOT EXISTS (SELECT 1 FROM tickets t WHERE t.booking_id = b.id)
                """).query(Long.class).single();

        assertThat(yes.get()).as("buyers told yes").isEqualTo(SEATS);
        assertThat(no.get()).as("buyers told no").isEqualTo(BUYERS - SEATS);
        assertThat(bookings).as("booking rows").isEqualTo(SEATS);
        assertThat(cheated).as("told yes but seat belongs to someone else").isZero();
    }
}
