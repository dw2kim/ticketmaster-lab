package lab.ticketmaster.event;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Step 7: a read-through cache in Redis (docs/design/05-deep-dives.md section 3).
 *
 *   1. GET key from Redis. Found (a "hit")? Return it. The database is not touched.
 *   2. Not found (a "miss")? Load from the database, SET key value EX ttl, return it.
 *
 * Values are stored as JSON. A TTL of 0 turns caching off (always load).
 * If Redis is down, every read is a miss: slower, but the page still works.
 */
@Component
public class ReadThroughCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public ReadThroughCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public <T> T get(String key, Duration ttl, Class<T> type, Supplier<T> loadFromDb) {
        if (ttl.isZero()) {
            return loadFromDb.get(); // cache off
        }
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return json.readValue(cached, type); // hit
            }
        } catch (DataAccessException redisDown) {
            return loadFromDb.get();
        }

        T value = loadFromDb.get(); // miss
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (DataAccessException redisDown) {
            // could not store it; next read is a miss again
        }
        return value;
    }
}
