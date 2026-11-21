package lab.ticketmaster.event;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import lab.ticketmaster.event.EventController.EventDetails;
import lab.ticketmaster.event.EventController.SeatMap;

/**
 * Step 7: Spring's cache abstraction (@Cacheable) backed by Redis.
 *
 * Point 2 - one TTL per cache. Each cache name gets its own RedisCacheConfiguration:
 *   event-details   cache.event-details-ttl (default 1 h)
 *   seat-map        cache.seat-map-ttl      (default 0 = off, see CacheSettings)
 * Values are stored as JSON (readable with redis-cli), under keys like "event-details:1".
 *
 * Stampede - cache.lock-on-miss (default false). When true, a LOCKING cache writer: on a miss
 * the first caller takes a lock in Redis (key "<cacheName>~lock"); other callers, even in other
 * app instances, wait (polling every 5 ms) and then read the fresh value. In Spring Data Redis 4,
 * @Cacheable(sync = true) alone does NOT do this: we measured ~57 identical seat-map queries
 * per expiry. Measured with a 1 s seat cache, 200 viewers, 30 s:
 *   no lock:  ~1,500 DB queries, ~5,600 requests/s, p95 ~70 ms
 *   lock:         66 DB queries, ~3,700 requests/s, p95 ~92 ms (every read also checks the lock)
 * Use the lock when the database is the bottleneck; here it is not, so it is off by default.
 * The lock expires after 5 s, so a crashed app cannot block the cache forever.
 *
 * Point 3 - Redis down. By default a cache error fails the request. Our CacheErrorHandler
 * logs a warning and lets the call go on to the database instead: slower, but it works.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory redis, CacheSettings settings) {
        RedisCacheWriter writer = settings.lockOnMiss()
                ? RedisCacheWriter.lockingRedisCacheWriter(redis,
                        Duration.ofMillis(5),                                  // how often waiters check
                        RedisCacheWriter.TtlFunction.just(Duration.ofSeconds(5)), // lock expires by itself
                        BatchStrategies.keys())
                : RedisCacheWriter.nonLockingRedisCacheWriter(redis);
        return RedisCacheManager.builder(writer)
                .withCacheConfiguration("event-details",
                        jsonCache(EventDetails.class).entryTtl(settings.detailsTtl()))
                .withCacheConfiguration("seat-map",
                        jsonCache(SeatMap.class).entryTtl(settings.seatMapTtl()))
                .disableCreateOnMissingCache() // a typo in a cache name fails loudly, not "cache forever"
                .build();
    }

    private static RedisCacheConfiguration jsonCache(Class<?> type) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(cacheName -> cacheName + ":") // key "event-details:1", not "event-details::1"
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(type)));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("cache {} unavailable, reading from the database: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("cache {} unavailable, value not stored: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("cache {} unavailable, could not evict: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("cache {} unavailable, could not clear: {}", cache.getName(), e.getMessage());
            }
        };
    }
}
