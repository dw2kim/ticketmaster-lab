package lab.ticketmaster.queue;

import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Step 6: a virtual waiting room in front of booking (docs/design/05-deep-dives.md section 4).
 *
 *   queue:{eventId}               sorted set, member = userId, score = arrival number (fair order)
 *   queue:{eventId}:seq           counter that hands out arrival numbers
 *   admitted:{eventId}:{userId}   "you may book now", deleted by Redis after queue.admitted-for
 *   queues                        set of event ids that have people waiting
 *
 * One key per admitted user (not one set of all admitted users), because a key can expire on
 * its own and a set member cannot. Same lesson as the sorted set in step 5b.
 *
 * Turned on with queue.enabled=true. When off, everyone may book (steps 2-5b behaviour).
 */
@Component
public class WaitingRoom {

    public record Spot(boolean admitted, Long position, long estimatedWaitSeconds) {}

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int admitPerTick;
    private final Duration tick;
    private final Duration admittedFor;

    public WaitingRoom(StringRedisTemplate redis,
                       @Value("${queue.enabled}") boolean enabled,
                       @Value("${queue.admit-per-second}") int admitPerTick,
                       @Value("${queue.tick:1s}") Duration tick,
                       @Value("${queue.admitted-for}") Duration admittedFor) {
        this.redis = redis;
        this.enabled = enabled;
        this.admitPerTick = admitPerTick;
        this.tick = tick;
        this.admittedFor = admittedFor;
    }

    static String queueKey(long eventId) { return "queue:" + eventId; }
    static String admittedKey(long eventId, String userId) { return "admitted:" + eventId + ":" + userId; }

    /** Get in line (once). Joining again keeps your place. */
    public Spot join(long eventId, String userId) {
        if (isAdmitted(eventId, userId)) {
            return new Spot(true, null, 0);
        }
        Long arrival = redis.opsForValue().increment(queueKey(eventId) + ":seq");
        redis.opsForZSet().addIfAbsent(queueKey(eventId), userId, arrival); // ZADD NX: keep first place
        redis.opsForSet().add("queues", Long.toString(eventId));
        return spot(eventId, userId);
    }

    /** Where am I? Clients ask this every few seconds while they wait. */
    public Spot spot(long eventId, String userId) {
        if (isAdmitted(eventId, userId)) {
            return new Spot(true, null, 0);
        }
        Long rank = redis.opsForZSet().rank(queueKey(eventId), userId);
        if (rank == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not in the queue; join first");
        }
        long position = rank + 1;
        long ticksToWait = (position + admitPerTick - 1) / admitPerTick; // round up
        return new Spot(false, position, ticksToWait * tick.toSeconds());
    }

    /** Let the next N people in. Called once per tick (queue.tick, default 1 s) by QueueAdmitter. */
    public int admitNext(long eventId) {
        Set<TypedTuple<String>> next = redis.opsForZSet().popMin(queueKey(eventId), admitPerTick); // ZPOPMIN
        if (next == null) {
            return 0;
        }
        next.forEach(t -> redis.opsForValue().set(admittedKey(eventId, t.getValue()), "1", admittedFor));
        return next.size();
    }

    /** Booking calls this first. 403 if the waiting room is on and you were not let in. */
    public void checkAdmitted(long eventId, String userId) {
        if (enabled && !isAdmitted(eventId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "waiting room is on: you have not been let in yet (join with POST /events/"
                            + eventId + "/queue, check with GET)");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    private boolean isAdmitted(long eventId, String userId) {
        return Boolean.TRUE.equals(redis.hasKey(admittedKey(eventId, userId)));
    }
}
