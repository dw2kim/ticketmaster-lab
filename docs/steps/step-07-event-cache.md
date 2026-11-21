# Step 7: fast reads (cache the event page)

**Design link:** `docs/design/05-deep-dives.md` section 3
**Idea in one sentence:** when everyone refreshes the same event page, answer from Redis
instead of asking Postgres every time, and cache each part only as long as it stays true.

## What we built

`GET /events/1` is two database reads: the event details and the seat map. Each one can now
come from a **read-through cache** in Redis:

```
1. look in Redis for event-details:1   -> found ("hit")?  return it, no database
2. not found ("miss")?                  -> read Postgres, store it with a TTL, return it
```

| Cache (Redis key) | What | Changes | TTL setting | Default |
| --- | --- | --- | --- | --- |
| `event-details:1` | name, venue, performer, date | almost never | `cache.event-details-ttl` | `PT1H` (on) |
| `seat-map:1` | status of every seat | every second | `cache.seat-map-ttl` | `PT0S` (off) |

- A long TTL is safe for data that does not change. A short TTL (or none) for data that does.
- **A stale seat map cannot oversell.** It can show a taken seat as free; the buyer then gets
  `409` from reserve, which always checks the database (step 4).
- No invalidation code: our events never change (no admin endpoints). A real system would
  also evict `event-details:1` when an admin edits the event (`@CacheEvict`).

### Two versions, same behaviour

**Version 1 (hand-written, now commented out):** `event/ReadThroughCache.java`. Every step
(get, miss, load, set) is a line of code you can read. Kept as comments to compare.

**Version 2 (in use): Spring's `@Cacheable` with a Redis `CacheManager`**, as a real app
would do it. The proxy around `EventReads` does the same get/miss/load/set for you:

```java
@Cacheable(cacheNames = "seat-map", key = "#id", sync = true,
           condition = "@cacheSettings.seatMapCached()")
public SeatMap seats(long id) { ... SELECT ... FROM tickets ... }
```

What a real app must remember, and where it is in the code:

| Point | What goes wrong otherwise | Where |
| --- | --- | --- |
| One TTL per cache | One TTL for everything; and in Spring's Redis cache **TTL 0 means "forever"**, not "off" | `CacheConfig` (TTL per cache name), `CacheSettings` (`condition` turns a cache off) |
| Redis down | By default a cache error fails the whole request | `CacheConfig.errorHandler()`: log a warning, read the database |
| Calls from inside the same class | `this.method()` skips the proxy: no caching, **no error** | Cached methods live in their own bean, `EventReads` |
| Cache stampede | `sync = true` alone does **not** help with Redis in Spring Data Redis 4 (we measured it) | `cache.lock-on-miss=true`: a locking cache writer (a lock in Redis) |

New files: `event/EventReads`, `event/CacheConfig`, `event/CacheSettings`,
`EventCacheTest` (4 tests), `CacheRedisDownTest`, `load/view.js`, `load/dbcalls.sql`.

## How to run it

Two terminal windows, both in the `ticketmaster-lab` folder. **Terminal 1 runs the app**
(stop it with `Ctrl+C`). **Terminal 2 is for everything else.**

### 0. Once, before you start

**Terminal 1:**

```bash
cd ~/ticketmaster-lab
```

```bash
git fetch origin && git checkout step-07-event-cache && git pull
```

```bash
docker compose up -d
```

**Terminal 2:**

```bash
cd ~/ticketmaster-lab
```

### Part A: see the cache, and a stale seat map

**Terminal 1.** Start the app with the seat map cached for **10 seconds** (long on purpose, so
you can see it be wrong). Wait for `Started TicketmasterApplication`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--cache.seat-map-ttl=PT10S
```

**Terminal 2.**

0. Start clean:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/reset.sql
```

```bash
docker compose exec redis redis-cli FLUSHALL
```

1. Open the event page once. This fills both caches. You should see A1 `"status":"available"`.

```bash
curl -s localhost:8080/events/1
```

2. Look inside Redis. You should see `event-details:1` and `seat-map:1`:

```bash
docker compose exec redis redis-cli KEYS '*'
```

How long each lives: about `10` for the seats, about `3600` for the details.

```bash
docker compose exec redis redis-cli TTL seat-map:1
```

```bash
docker compose exec redis redis-cli TTL event-details:1
```

What is stored (JSON):

```bash
docker compose exec redis redis-cli GET event-details:1
```

3. Ana reserves A1. You should see a `bookingId`:

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ana' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

4. **Right away** (within 10 seconds), open the page again. A1 still says `"available"`.
   That is the stale cache:

```bash
curl -s localhost:8080/events/1
```

5. Ben believes the page and tries A1. You should see `409` "seat already held or booked".
   The stale page did not fool the database:

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ben' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

6. Wait 10 seconds, open the page again (step 4's command). Now A1 says `"reserved"`.

7. Stop Redis, then open the page. It still works (status `200`), just slower (about 2 s:
   each of the two cache reads waits 1 s for Redis before giving up):

```bash
docker compose stop redis
```

```bash
curl -s -o /dev/null -w "%{http_code} in %{time_total}s\n" localhost:8080/events/1
```

In **Terminal 1** you see the CacheErrorHandler at work:
`cache event-details unavailable, reading from the database: Redis command timed out`.
Start Redis again:

```bash
docker compose start redis
```

Done with Part A: **Terminal 1**, `Ctrl+C`.

### Part B: measure the speed-up

Three runs, each with the same moves: (1) start the app in Terminal 1, (2) note the DB
counter, run k6, note the counter again in Terminal 2, (3) `Ctrl+C` in Terminal 1.

| Run | Terminal 1 command |
| --- | --- |
| no cache | `./mvnw spring-boot:run "-Dspring-boot.run.arguments=--cache.event-details-ttl=PT0S --cache.seat-map-ttl=PT0S"` |
| details cached (the default) | `./mvnw spring-boot:run` |
| details + seat map (1 s) | `./mvnw spring-boot:run -Dspring-boot.run.arguments=--cache.seat-map-ttl=PT1S` |
| same, with the stampede lock | `./mvnw spring-boot:run "-Dspring-boot.run.arguments=--cache.seat-map-ttl=PT1S --cache.lock-on-miss=true"` |

**Terminal 2**, for each run:

1. Note the number of database transactions so far:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/dbcalls.sql
```

2. 200 viewers refresh the page for 30 seconds:

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/view.js
```

3. Run step 1's command again. Subtract: that many database calls served the k6 requests.
   Divide by `http_reqs` to get **DB calls per request**.

In the k6 summary, look at `http_reqs` (the second number is requests per second) and
`http_req_duration` (p50, p95, p99).

### When you are done

```bash
docker compose down -v
```

### If something goes wrong

| You see | Why | Fix |
| --- | --- | --- |
| `curl: (7) Failed to connect` | The app is not running | Start it in Terminal 1, wait for `Started` |
| `KEYS` shows `(empty array)` | Caches not filled yet, or you ran `FLUSHALL` after step 1 | Open the page once (step 1) |
| A1 never shows stale | You waited more than 10 s, or started the app without `--cache.seat-map-ttl=PT10S` | Restart with the Part A command |
| `409` for Ana in step 3 | A1 is taken from an earlier try | Run the two reset commands (step 0) |

## Numbers

200 viewers, 30 seconds, `load/view.js`, after a 10 s warm-up, lab machine (4 CPUs; k6 runs on
the same machine). Two runs each. "DB calls" = Postgres transactions during the 30 s.

**Version 2 (`@Cacheable`, in use):**

| Cache | Requests/s | DB calls per request | DB calls in 30 s | p50 | p95 | p99 |
| --- | --- | --- | --- | --- | --- | --- |
| none | 3,568 / 3,587 | 1.97 / 1.99 | ~213,000 | 51 / 50 ms | 150 / 147 ms | 208 / 204 ms |
| details (1 h) | 3,894 / 3,872 | 1.00 / 0.99 | ~116,000 | 46 / 45 ms | 109 / 111 ms | 166 / 174 ms |
| details + seats (1 s) | 5,533 / 5,726 | 0.008 / 0.010 | 1,388 / 1,662 | 32 / 32 ms | 72 / 68 ms | 116 / 104 ms |
| details + seats (1 s) + lock | 3,538 / 3,828 / 3,647 | 0.0005 | **66 / 62 / 64** | 40 / 38 / 39 ms | 91 / 94 / 99 ms | 297 / 440 / 543 ms |

**Version 1 (hand-written), for comparison:** none 3,464 / 3,352 req/s, p95 152 / 158 ms;
details 3,629 / 3,833, p95 113 ms; details + seats (1 s) 5,466 / 5,260, p95 71 / 73 ms,
1,424 / 2,046 DB calls. Same speed as version 2: `@Cacheable` adds no measurable cost here.

0 failed requests in all runs.

Demos: with a 10 s seat cache, Ana reserved A1, the page still showed A1 `available`, Ben's
reserve got `409`, and 10 s later the page showed `reserved`. With Redis stopped, the page
returned `200` in about 2 s, from the database.

## What surprised us

- Caching the details halved the database calls (2 -> 1 per request) but sped things up only
  a little: the details query is tiny. The seat map is the expensive part.
- Caching the seat map for just **1 second** cut database calls by about 99% and raised
  throughput by about 55%. Even a very short TTL helps when thousands read the same key.
- **Cache stampede:** with a 1 s seat cache we expected about 30 database calls in 30 s (one
  per expiry). We measured 1,400 to 2,000. When the key expires, many of the 200 viewers miss
  at the same moment and all ask Postgres together. Postgres's statement log showed 571 copies
  of the seat-map query in 10 s.
- **`sync = true` did not fix it.** Spring suggests it, but in Spring Data Redis 4 a Redis
  cache only refills once per miss with a *locking* cache writer. With the lock: 66 database
  calls instead of ~1,500. The price: every read also checks the lock, so throughput fell from
  ~5,600 to ~3,700 requests/s and p99 rose to 300-540 ms. A lock protects the database at the
  cost of every reader. Off by default here, because our database is not the bottleneck.
- **TTL 0 means "forever"** in Spring's Redis cache. "Off" needs a `condition`, not a TTL.
- With caching, the app itself (and k6 on the same machine) becomes the limit, not Postgres.
- `@Cacheable` was as fast as the hand-written version. Less code, same result, but you have
  to know the four points above.

## Quiz (multiple choice)

### Q1. Why cache the event details for an hour but the seat map for at most a second (or not at all)?

- A) Redis cannot store lists for long.
- B) Details almost never change, so an old copy is still right; seats change every second, so an old copy is soon wrong.
- C) The seat map is bigger, and big keys must expire fast.
- D) Postgres is faster for seats.

<details><summary>Answer</summary>

**B.** Pick the TTL from how fast the data changes and how bad a stale copy is. Redis stores
lists fine (not A). Size is not the reason (not C). Postgres was slower than the cache for
both (not D).
</details>

### Q2. The cached seat map shows A1 free, but Ana took it 2 seconds ago. Ben clicks A1. What happens?

- A) Ben gets A1 too: the cache said it was free.
- B) Ben gets 409: reserve checks the database, not the cache.
- C) The cache notices and refreshes itself.
- D) Ana loses A1.

<details><summary>Answer</summary>

**B.** The cache only feeds the page people look at. Every write goes through the database's
conditional update (step 4), so a stale page costs Ben a click, never a double sale. A and D
cannot happen. Nothing refreshes the key before its TTL (not C).
</details>

### Q3. With a 1 second seat cache, why were there about 2,000 database calls in 30 s instead of about 30?

- A) Redis lost the key many times.
- B) k6 bypassed the cache.
- C) Each time the key expired, many viewers missed at the same moment and all loaded from Postgres together (a cache stampede).
- D) The event details were not cached.

<details><summary>Answer</summary>

**C.** A read-through cache lets every miss go to the database. With 200 viewers, one expiry
means dozens of misses at once. Redis kept the key until its TTL (not A). k6 only calls the
app (not B). Details were cached; the counter shows it (not D).
</details>

## What to understand

- Read-through cache: try Redis, on a miss load from the database and store with a TTL.
- Choose the TTL per piece of data: long for what rarely changes, short or none for what
  changes. Even 1 second helps a lot under heavy reads.
- A stale read is fine as long as **writes** check the source of truth. The seat map can
  lie for a second; reserve never does.
