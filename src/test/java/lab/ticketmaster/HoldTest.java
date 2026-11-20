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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

import lab.ticketmaster.booking.HoldService;
import lab.ticketmaster.booking.HoldService.HoldExpiredException;

/** Step 5: reserve, confirm, expire. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class HoldTest {

    @Autowired HoldService holds;
    @Autowired JdbcClient db;

    @BeforeEach
    void resetSeats() {
        db.sql("UPDATE tickets SET status = 'available', booking_id = NULL, reserved_until = NULL").update();
        db.sql("DELETE FROM bookings").update();
    }

    @Test
    void reserveThenConfirmSellsTheSeat() {
        var hold = holds.reserve(1, "ana", List.of(1L));
        assertThat(status(1)).isEqualTo("reserved");

        var done = holds.confirm(hold.bookingId(), "ana");
        assertThat(done.status()).isEqualTo("confirmed");
        assertThat(status(1)).isEqualTo("booked");
    }

    @Test
    void secondBuyerCannotTakeAHeldSeat() {
        holds.reserve(1, "ana", List.of(1L));
        assertThatThrownBy(() -> holds.reserve(1, "ben", List.of(1L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void confirmTwiceIsSafe() {
        var hold = holds.reserve(1, "ana", List.of(1L));
        holds.confirm(hold.bookingId(), "ana");
        var again = holds.confirm(hold.bookingId(), "ana");
        assertThat(again.status()).isEqualTo("confirmed");
        assertThat(count("SELECT count(*) FROM tickets WHERE status = 'booked'")).isEqualTo(1);
    }

    @Test
    void expiredHoldCanBeTakenAndTheLateBuyerLoses() {
        var anaHold = holds.reserve(1, "ana", List.of(1L));
        expireAllHolds(); // Ana went for coffee

        var benHold = holds.reserve(1, "ben", List.of(1L)); // the seat is free again
        holds.confirm(benHold.bookingId(), "ben");

        assertThatThrownBy(() -> holds.confirm(anaHold.bookingId(), "ana"))
                .isInstanceOf(HoldExpiredException.class);
        assertThat(bookingStatus(anaHold.bookingId())).isEqualTo("expired");
        assertThat(status(1)).isEqualTo("booked");
    }

    @Test
    void expiredHoldIsNotSoldEvenIfNobodyElseTookIt() {
        var hold = holds.reserve(1, "ana", List.of(1L));
        expireAllHolds();

        assertThatThrownBy(() -> holds.confirm(hold.bookingId(), "ana"))
                .isInstanceOf(HoldExpiredException.class);
        assertThat(status(1)).isEqualTo("available");
    }

    @Test
    void multiSeatBookingIsAllOrNothing() {
        var ana = holds.reserve(1, "ana", List.of(1L, 2L));
        expireAllHolds();
        holds.reserve(1, "ben", List.of(2L)); // Ben takes one of Ana's expired seats

        assertThatThrownBy(() -> holds.confirm(ana.bookingId(), "ana"))
                .isInstanceOf(HoldExpiredException.class);
        assertThat(status(1)).isEqualTo("available"); // not sold half a booking
        assertThat(count("SELECT count(*) FROM tickets WHERE status = 'booked'")).isZero();
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
        assertThat(count("SELECT count(*) FROM tickets WHERE status = 'reserved'")).isEqualTo(20);
        assertThat(count("SELECT count(*) FROM bookings WHERE status = 'in_progress'")).isEqualTo(20);
    }

    private void expireAllHolds() {
        db.sql("UPDATE tickets SET reserved_until = now() - interval '1 second' WHERE status = 'reserved'")
                .update();
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
