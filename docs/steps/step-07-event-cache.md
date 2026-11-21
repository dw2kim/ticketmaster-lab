# Step 7: fast reads (cache the event page)

**Design link:** `docs/design/05-deep-dives.md` section 3
**Idea in one sentence:** when everyone refreshes the same event page, answer from Redis
instead of asking Postgres every time, and cache each part only as long as it stays true.

## What we built

`GET /events/1` is two database reads: the event details and the seat map. Now each one can
come from a **read-through cache** in Redis (`ReadThroughCache`):

```
1. GET event:1:details from Redis    -> found ("hit")?  return it, no database
2. not found ("miss")?               -> read Postgres, SET event:1:details <json> EX <ttl>
```

| Cache key | What | Changes | TTL setting | Default |
| --- | --- | --- | --- | --- |
| `event:1:details` | name, venue, performer, date | almost never | `cache.event-details-ttl` | `PT1H` (on) |
| `event:1:seats` | status of every seat | every second | `cache.seat-map-ttl` | `PT0S` (off) |

- A long TTL is safe for data that does not change. A short TTL (or none) for data that does.
- **A stale seat map cannot oversell.** It can show a taken seat as free; the buyer then gets
  `409` from reserve, which always checks the database (step 4).
- If Redis is down, every read is a miss: slower, but the page still works.
- No invalidation code: our events never change (no admin endpoints). A real system would
  also delete `event:1:details` when an admin edits the event.

New files: `event/ReadThroughCache`, `EventCacheTest` (3 tests), `load/view.js`,
`load/dbcalls.sql`.

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

2. Look inside Redis. You should see `event:1:seats` and `event:1:details`:

```bash
docker compose exec redis redis-cli KEYS 'event:*'
```

How long each lives: about `10` for the seats, about `3600` for the details.

```bash
docker compose exec redis redis-cli TTL event:1:seats
```

```bash
docker compose exec redis redis-cli TTL event:1:details
```

What is stored (JSON):

```bash
docker compose exec redis redis-cli GET event:1:details
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

Done with Part A: **Terminal 1**, `Ctrl+C`.

### Part B: measure the speed-up

Three runs, each with the same moves: (1) start the app in Terminal 1, (2) note the DB
counter, run k6, note the counter again in Terminal 2, (3) `Ctrl+C` in Terminal 1.

| Run | Terminal 1 command |
| --- | --- |
| no cache | `./mvnw spring-boot:run "-Dspring-boot.run.arguments=--cache.event-details-ttl=PT0S --cache.seat-map-ttl=PT0S"` |
| details cached (the default) | `./mvnw spring-boot:run` |
| details + seat map (1 s) | `./mvnw spring-boot:run -Dspring-boot.run.arguments=--cache.seat-map-ttl=PT1S` |

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

200 viewers, 30 seconds, `load/view.js`, after a 10 s warm-up, 2 runs each, lab machine
(4 CPUs; k6 runs on the same machine).

| Cache | Requests/s | DB calls per request | p50 | p95 | p99 |
| --- | --- | --- | --- | --- | --- |
| none | 3,464 / 3,352 | 1.97 / 1.96 | 52 ms | 152 / 158 ms | 216 / 226 ms |
| details (1 h) | 3,629 / 3,833 | 0.99 / 1.00 | 50 / 46 ms | 113 ms | 172 / 171 ms |
| details + seats (1 s) | 5,466 / 5,260 | 0.012 / 0.009 | 33 / 34 ms | 71 / 73 ms | 105 / 119 ms |

0 failed requests in all runs.

Stale seat map demo (10 s seat cache): Ana reserved A1; the page still showed A1
`available`; Ben's reserve got `409`; 10 s later the page showed `reserved`.

## What surprised us

- Caching the details halved the database calls (2 -> 1 per request) but sped things up only
  a little: the details query is tiny. The seat map is the expensive part.
- Caching the seat map for just **1 second** cut database calls by about 99% and raised
  throughput by about 55%. Even a very short TTL helps when thousands read the same key.
- **Cache stampede:** with a 1 s seat cache we expected about 30 database calls in 30 s (one
  per expiry). We measured 1,400 to 2,000. When the key expires, many of the 200 viewers miss
  at the same moment and all ask Postgres together. Real systems fix this by letting only one
  request refill the key (a lock or "single flight") or by refreshing it just before it expires.
- With caching, the app itself (and k6 on the same machine) becomes the limit, not Postgres.

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
