package lab.ticketmaster.booking;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Step 5b: holds as a distributed lock with TTL in Redis (booking.hold-store=redis).
 *
 *   hold:      SET ticket:{ticketId} {bookingId} NX EX {seconds}
 *              NX = only if nobody has it. EX = Redis deletes it by itself after N seconds.
 *   seat map:  ZADD event:{eventId}:held {expiresAtMillis} {ticketId}
 *              A sorted set scored by expiry, so expired holds drop out of the seat map by
 *              themselves (a plain set would keep "ghost" holds forever).
 *   sale:      Postgres. tickets only ever say available | booked here. The final UPDATE
 *              still checks status = 'available' (step 4), so a lost lock cannot double-sell.
 */
@Service("redis")
public class RedisHolds implements Holds {

    private final JdbcClient db;
    private final StringRedisTemplate redis;
    private final Duration holdFor;

    public RedisHolds(JdbcClient db, StringRedisTemplate redis,
                      @Value("${booking.hold-duration}") Duration holdFor) {
        this.db = db;
        this.redis = redis;
        this.holdFor = holdFor;
    }

    static String lockKey(long ticketId) {
        return "ticket:" + ticketId;
    }

    static String heldKey(long eventId) {
        return "event:" + eventId + ":held";
    }

    @Override
    @Transactional
    public Hold reserve(long eventId, String userId, List<Long> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketIds is empty");
        }
        List<Long> ids = ticketIds.stream().sorted().toList();

        // Sold seats cannot be held. (Held seats are checked by Redis below.)
        long free = db.sql("""
                SELECT count(*) FROM tickets
                 WHERE event_id = :eventId AND id IN (:ids) AND status = 'available'
                """)
                .param("eventId", eventId).param("ids", ids)
                .query(Long.class).single();
        if (free != ids.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "seat already booked");
        }

        long bookingId = db.sql("""
                INSERT INTO bookings (user_id, status, seat_count, ticket_ids)
                VALUES (:userId, 'in_progress', :seats, :ticketIds)
                RETURNING id
                """)
                .param("userId", userId)
                .param("seats", ids.size())
                .param("ticketIds", ids.toArray(new Long[0]))
                .query(Long.class).single();

        // Take one lock per seat. If any is taken, give back the ones we got.
        List<Long> got = new ArrayList<>();
        for (long id : ids) {
            Boolean ok = redis(() -> redis.opsForValue()
                    .setIfAbsent(lockKey(id), Long.toString(bookingId), holdFor));
            if (!Boolean.TRUE.equals(ok)) {
                got.forEach(g -> releaseIfOurs(g, bookingId));
                throw new ResponseStatusException(HttpStatus.CONFLICT, "seat already held");
            }
            got.add(id);
        }

        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(holdFor);
        long score = expiresAt.toInstant().toEpochMilli();
        redis(() -> {
            ids.forEach(id -> redis.opsForZSet().add(heldKey(eventId), Long.toString(id), score));
            return null;
        });
        return new Hold(bookingId, expiresAt);
    }

    @Override
    @Transactional(noRollbackFor = HoldExpiredException.class) // keep the "expired" status
    public Confirmed confirm(long bookingId, String userId) {
        record Booking(String status, List<Long> ticketIds) {}
        Booking booking = db.sql("""
                SELECT status, ticket_ids FROM bookings
                 WHERE id = :id AND user_id = :userId
                   FOR UPDATE
                """)
                .param("id", bookingId).param("userId", userId)
                .query((rs, n) -> new Booking(rs.getString("status"),
                        rs.getArray("ticket_ids") == null ? List.of()
                                : List.of((Long[]) rs.getArray("ticket_ids").getArray())))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such booking"));

        if (booking.status().equals("confirmed")) {
            return new Confirmed(bookingId, "confirmed"); // second call: same answer
        }
        if (!booking.status().equals("in_progress")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "booking is " + booking.status());
        }

        // 1. Do we still hold every lock? (TTL may have run out, or Redis may have lost it.)
        for (long id : booking.ticketIds()) {
            String owner = redis(() -> redis.opsForValue().get(lockKey(id)));
            if (!Long.toString(bookingId).equals(owner)) {
                expire(bookingId, booking.ticketIds());
            }
        }

        // fake payment: pretend the card was charged here

        // 2. The final guard is the database (step 4's conditional update). Even if a lock was
        //    lost after the check above and someone else got in first, only one sale wins.
        int sold = db.sql("""
                UPDATE tickets
                   SET status = 'booked', booking_id = :bookingId, version = version + 1
                 WHERE id IN (:ids) AND status = 'available'
                """)
                .param("bookingId", bookingId).param("ids", booking.ticketIds())
                .update();
        if (sold != booking.ticketIds().size()) {
            // Undo the seats we did get (all or none), then expire.
            db.sql("""
                    UPDATE tickets SET status = 'available', booking_id = NULL
                     WHERE booking_id = :bookingId AND status = 'booked'
                    """).param("bookingId", bookingId).update();
            expire(bookingId, booking.ticketIds());
        }

        db.sql("UPDATE bookings SET status = 'confirmed' WHERE id = :id").param("id", bookingId).update();

        // 3. Sold: the locks are not needed any more.
        long eventId = db.sql("SELECT event_id FROM tickets WHERE id = :id")
                .param("id", booking.ticketIds().getFirst()).query(Long.class).single();
        booking.ticketIds().forEach(id -> {
            releaseIfOurs(id, bookingId);
            redis(() -> redis.opsForZSet().remove(heldKey(eventId), Long.toString(id)));
        });
        return new Confirmed(bookingId, "confirmed");
    }

    /** Held seats for the seat map. If Redis is down, show none held (seats look free). */
    @Override
    public Set<Long> heldNow(long eventId) {
        try {
            double now = System.currentTimeMillis();
            redis.opsForZSet().removeRangeByScore(heldKey(eventId), 0, now); // lazy cleanup
            Set<String> held = redis.opsForZSet().rangeByScore(heldKey(eventId), now, Double.MAX_VALUE);
            return held == null ? Set.of()
                    : held.stream().map(Long::valueOf).collect(Collectors.toSet());
        } catch (DataAccessException redisDown) {
            return Set.of();
        }
    }

    private void expire(long bookingId, List<Long> ticketIds) {
        ticketIds.forEach(id -> releaseIfOurs(id, bookingId));
        db.sql("UPDATE bookings SET status = 'expired' WHERE id = :id").param("id", bookingId).update();
        throw new HoldExpiredException();
    }

    /** Delete the lock only if it is still ours (a new buyer may hold it now). */
    private void releaseIfOurs(long ticketId, long bookingId) {
        try {
            String key = lockKey(ticketId);
            if (Long.toString(bookingId).equals(redis.opsForValue().get(key))) {
                redis.delete(key); // check-then-delete has a tiny gap; a Lua script would close it
            }
        } catch (DataAccessException redisDown) {
            // the TTL will clean it up
        }
    }

    /** Redis errors become 503, so the buyer sees "try again later", not a stack trace. */
    private static <T> T redis(Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException redisDown) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "holds unavailable: Redis is down");
        }
    }
}
