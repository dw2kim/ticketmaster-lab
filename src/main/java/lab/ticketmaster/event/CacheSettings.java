package lab.ticketmaster.event;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Step 7: how long each cache keeps its value. PT0S means "do not cache".
 *
 * Why not just give Redis a TTL of 0? In Spring's Redis cache, a TTL of 0 means "keep
 * forever", the opposite of what we want. So 0 turns the cache off through the
 * condition on @Cacheable in EventReads (e.g. "@cacheSettings.seatMapCached()").
 */
@Component("cacheSettings")
public class CacheSettings {

    private final Duration detailsTtl;
    private final Duration seatMapTtl;
    private final boolean lockOnMiss;

    public CacheSettings(@Value("${cache.event-details-ttl}") Duration detailsTtl,
                         @Value("${cache.seat-map-ttl}") Duration seatMapTtl,
                         @Value("${cache.lock-on-miss}") boolean lockOnMiss) {
        this.detailsTtl = detailsTtl;
        this.seatMapTtl = seatMapTtl;
        this.lockOnMiss = lockOnMiss;
    }

    public Duration detailsTtl() { return detailsTtl; }
    public Duration seatMapTtl() { return seatMapTtl; }
    public boolean detailsCached() { return !detailsTtl.isZero(); }
    public boolean seatMapCached() { return !seatMapTtl.isZero(); }
    public boolean lockOnMiss() { return lockOnMiss; }
}
