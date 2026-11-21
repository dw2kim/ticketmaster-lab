package lab.ticketmaster.event;

// NOT USED ANY MORE (step 7, second version).
//
// This was the first version of step 7: a read-through cache written by hand, so every step
// (look in Redis, on a miss read the database, store with a TTL) is visible.
// It is replaced by Spring's @Cacheable with a Redis CacheManager, as a real app would do it:
//   EventReads   - the cached methods (@Cacheable, sync = true)
//   CacheConfig  - one TTL per cache, JSON values, CacheErrorHandler for "Redis down"
//   CacheSettings - TTL 0 turns a cache off
// Kept as comments to compare the two. Read this file to see what @Cacheable does for you.
//
// import java.time.Duration;
// import java.util.function.Supplier;
//
// import org.springframework.dao.DataAccessException;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.stereotype.Component;
//
// import tools.jackson.databind.ObjectMapper;
//
// /**
//  * Step 7: a read-through cache in Redis (docs/design/05-deep-dives.md section 3).
//  *
//  *   1. GET key from Redis. Found (a "hit")? Return it. The database is not touched.
//  *   2. Not found (a "miss")? Load from the database, SET key value EX ttl, return it.
//  *
//  * Values are stored as JSON. A TTL of 0 turns caching off (always load).
//  * If Redis is down, every read is a miss: slower, but the page still works.
//  */
// @Component
// public class ReadThroughCache {
//
//     private final StringRedisTemplate redis;
//     private final ObjectMapper json;
//
//     public ReadThroughCache(StringRedisTemplate redis, ObjectMapper json) {
//         this.redis = redis;
//         this.json = json;
//     }
//
//     public <T> T get(String key, Duration ttl, Class<T> type, Supplier<T> loadFromDb) {
//         if (ttl.isZero()) {
//             return loadFromDb.get(); // cache off
//         }
//         try {
//             String cached = redis.opsForValue().get(key);
//             if (cached != null) {
//                 return json.readValue(cached, type); // hit
//             }
//         } catch (DataAccessException redisDown) {
//             return loadFromDb.get();
//         }
//
//         T value = loadFromDb.get(); // miss
//         try {
//             redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
//         } catch (DataAccessException redisDown) {
//             // could not store it; next read is a miss again
//         }
//         return value;
//     }
// }
