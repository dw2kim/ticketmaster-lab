package lab.ticketmaster.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every tick (queue.tick, default 1 s), let the next queue.admit-per-second people in, for every
 * event with a queue. A longer tick (e.g. 30s) makes the waiting visible when you try it by hand.
 */
@Component
@ConditionalOnProperty(name = "queue.enabled", havingValue = "true")
public class QueueAdmitter {

    private final WaitingRoom room;
    private final StringRedisTemplate redis;

    public QueueAdmitter(WaitingRoom room, StringRedisTemplate redis) {
        this.room = room;
        this.redis = redis;
    }

    @Scheduled(fixedRateString = "${queue.tick:1s}", initialDelayString = "${queue.tick:1s}")
    public void tick() {
        var events = redis.opsForSet().members("queues");
        if (events != null) {
            events.forEach(id -> room.admitNext(Long.parseLong(id)));
        }
    }
}
