# 05: Deep dives

Each deep dive starts from a non-functional requirement. The options are summarized in our
own words from the source. In the lab we **run** them, not just discuss them.

---

## Section 1: No double booking under contention (steps 3-4)

**Problem.** Two buyers read "seat 12 is available" at the same moment, and both write
"sold". This is a read-check-write race.

**Options we will run and compare:**

| Option | Idea | Trade-off |
| --- | --- | --- |
| Pessimistic row lock | `SELECT ... FOR UPDATE` then update, in one *short* transaction | Simple and correct; buyers queue on hot rows |
| Optimistic concurrency (OCC) | `UPDATE ... WHERE id=? AND version=?`; retry or fail on 0 rows | No waiting; many failures when contention is high |
| Atomic conditional update | `UPDATE tickets SET status='booked' WHERE id=? AND status='available'` and check the row count | Smallest; the database does the check-and-set in one statement |

Isolation level matters too. Also try running the naive version under `SERIALIZABLE`.

This database-level guard stays in place in every later step. Even when Redis holds the seat
(section 2), the final write to `booked` must still be protected by one of these.

**Measure:** sold count (must equal the number of tickets), rejected count, throughput,
p50/p95 latency.

**Lab result (step 4):** all three sold exactly 20 of 20 with 0 cheated buyers; naive told
28-34 buyers "yes". Default is the conditional update (ADR-0003). Isolation-level
experiments (`SERIALIZABLE`) were not run; optional.

---

## Section 2: Reserving tickets during checkout (step 5)

**Problem.** No one should spend 5 minutes on a payment form and then lose the seat. We need
three things:
1. hold the seat for the buyer during checkout,
2. release it if they leave,
3. mark it sold and confirm the booking if they pay.

### Bad: a long-running DB lock ("interactive transaction")

`SELECT ... FOR UPDATE` and keep the transaction open while the user types their card.

- Database locks are meant for near-instant transactions. Holding one for minutes ties up
  connections and raises the risk of contention and deadlocks.
- No good way to release on abandonment. You depend on session timeouts, and a crashed app or
  dropped connection can leave the lock in an unclear state.
- `lock_timeout` only turns waiting into an error for the *next* buyer; it doesn't queue them.

*Lab:* worth a 5-minute demo to see connections pile up, then move on.

### Good: status + `reserved_until`, released by a cron job

Ticket states `available -> reserved -> booked`. Reserving sets `status='reserved'` and an
expiry time. A cron job periodically sets expired holds back to `available`.

- **Lag:** a seat stays "reserved" after its hold ends until the next cron run. That's bad
  for hot events.
- **Reliability:** if cron stalls or fails, seats look taken when they aren't. That costs
  sales and annoys users.

### Great: implicit expiry (status is computed, not swept)

A ticket can be taken if `status='available' OR (status='reserved' AND reserved_until < now())`.
Reserve with one **short** transaction:

```sql
UPDATE tickets
   SET status = 'reserved', reserved_until = now() + interval '10 minutes', booking_id = $1
 WHERE id = $2
   AND (status = 'available' OR (status = 'reserved' AND reserved_until < now()));
-- 1 row -> you hold it; 0 rows -> someone else does
```

- Only one buyer can win, and an expired hold can be claimed right away. No cron is needed
  for correctness.
- Cost: every read filters on two columns, so add a compound index (`event_id, status,
  reserved_until`). Raw rows can also mislead other readers of the table, because some
  "reserved" rows are really expired. An optional sweep job tidies them, and if it runs late
  nothing breaks.

### Great: Redis lock with TTL (the design's pick)

`SET ticket:{ticketId} {userId} NX EX 600`. `SET ... NX` is atomic, so only one client gets
the key.

- **Why Redis when Postgres is already consistent?** Postgres has no per-row TTL. Redis
  expires keys on its own, and it's in memory, so taking and releasing locks stays fast
  under heavy concurrency.
- **The ticket table drops back to two states:** `available | booked`. The hold lives only in
  Redis, and its value is the user id, so confirm can check that it's the same user who
  reserved.
- **Several seats:** take the locks one by one; if any fails, release the ones you already
  hold. A Lua script can make it atomic when all the keys sit on one Redis node.
- **On success:** the DB transaction marks the ticket `booked`, then the app deletes the lock
  early.

Challenges to handle:

| Challenge | What to do |
| --- | --- |
| **Seat map must show held seats.** A plain Redis set of held ids leaves ghost seats after abandoned checkouts, because set members don't expire with the lock keys. | Use a sorted set scored by expiry: `ZADD event:{eventId}:reserved <expiresAt> <ticketId>`. Reads count only future scores; trim old ones with `ZREMRANGEBYSCORE`. Alternative: also write `reserved` to the DB and sweep it, with Redis's TTL as the real expiry. |
| **Redis goes down.** | Still no double booking: the DB guard from section 1 (OCC or a row lock) catches it. The experience gets worse, because a buyer may fail *after* paying and need a refund. That beats cron failing, which makes every seat look taken. |
| **The hold expires while payment is in flight.** A pays at minute 11 and B grabbed the seat at minute 10. | The DB write for one of them fails (OCC), and that payment is refunded automatically. Make the TTL generous, and **extend the lock when payment starts**. |

**Lab result (step 5):** implicit expiry is built (ADR-0004). 200 buyers reserving at once
got exactly 20 holds in 3 of 3 runs; a 30 second hold expired with no job. The Redis lock is
step 5b.

**Lab result (step 5b):** the Redis lock is built too (`booking.hold-store=redis`). Same
correctness (20 of 200 held, 3 of 3 runs; a lost lock is caught by the DB guard), same speed
within noise. Default stays Postgres (ADR-0005).

**The lab builds both greats** and compares them: seats stuck in limbo, failures after
payment, and what happens when Redis is killed mid-sale.

**Booking flow with holds:** reserve (lock + booking `in_progress`) -> pay -> webhook -> one
transaction sets ticket `booked` + booking `confirmed`, idempotent on `bookingId`. If that
transaction fails because the hold was lost, refund.

---

## Section 3: Scaling the view API for popular events (step 7, proposed)

**Problem.** Everyone keeps refreshing the same event page.

- **Cache what's read a lot and rarely changes:** event details, venue, performer.
  Read-through cache keyed `event:{eventId}`. On a miss, read the DB and fill the cache.
- **Invalidation:** use long TTLs for static data, short TTLs for anything about
  availability. Clear entries on write (in the design, DB triggers notify the cache).
- **Load balancing** (round robin / least connections) and **horizontal scaling**: the Event
  Service is stateless, so add instances.
- Challenges: keeping cache and DB in step (rare here, since event data seldom changes), and
  the work of running many instances (deploys, rollbacks).

**Measure:** requests/sec and p95 with and without the cache; DB queries per request.

**Lab result (step 7):** read-through cache in Redis. Details cached (1 h): DB calls per
request 2 -> 1, p95 155 -> 113 ms. Plus seat map (1 s): 0.01 DB calls per request, about
5,400 vs 3,400 requests/s, p95 72 ms. A stale seat map never oversold (reserve got 409).
Seen: cache stampede at each expiry. Seat map cache off by default (ADR-0007).

---

## Section 4: High-demand events: millions booking at once (step 6)

**Problem.** The seat map goes stale in seconds, and everyone clicks seats that are already
gone. The step from good to great here is often a *product* decision more than a harder
technical one.

| Rating | Option | Why |
| --- | --- | --- |
| Good | Push seat changes to clients with **SSE** (one-way, server -> client) | Fresher map, but at the biggest events every seat vanishes almost at once, which is confusing |
| Great | **Virtual waiting queue** that an admin turns on per event | Protects the booking path and gives a fair, calm experience |

How the waiting queue works:

1. A user asks for the booking page and is added to a Redis sorted set scored by arrival
   time. The client keeps an SSE (or WebSocket) connection open for position updates.
2. Users are let in from the front of the queue on a timer, or as tickets sell. They're told
   over their connection that it's their turn.
3. Admitted users go into `admitted:{eventId}` with a TTL. The **Booking Service rejects
   reserve requests from anyone not in that set**, so the queue can't be skipped.

Challenge: long or badly estimated waits frustrate people, so keep sending position and
estimated wait.

The lab builds the waiting room and runs a ~10,000-buyer spike with and without it.

**Lab result (step 6):** booking path p95 went from 30 s (timeouts) to 4-9 ms behind the
waiting room, with 0 errors behind it. About 1,800 errors stayed at the very first join,
because the queue runs inside the same app; real waiting rooms live at the edge. Polling
instead of SSE (ADR-0006).

---

## Section 5: Low-latency search (step 8, proposed)

**Problem.** `WHERE name LIKE '%Taylor%' OR description LIKE '%Taylor%'` scans the whole table.

| Rating | Option | Watch out for |
| --- | --- | --- |
| Good | B-tree indexes on what you filter by (name, date, performer, location); tune queries with `EXPLAIN`, no `SELECT *`, `LIMIT`, and `UNION` instead of `OR` | B-trees don't help `'%Taylor%'`; every index slows writes and costs storage |
| Great | Full-text search in the DB: Postgres `tsvector` + GIN index | Extra storage and special query syntax; not Lucene, so no fuzzy matching |
| Great | A dedicated search engine (Elasticsearch, built on inverted indexes) kept in sync from Postgres via **CDC** | Fuzzy search handles typos ("Tayler Swift"); keeping it in sync and running the cluster costs effort |

The lab does the Postgres options. Elasticsearch is a stretch goal.

---

## Section 6: Repeated search queries (step 8, proposed)

| Rating | Option | Watch out for |
| --- | --- | --- |
| Good | Cache results in Redis. Build the **key from every parameter that can change the result** (e.g. `search:keyword=...&start=...&end=...`) and set a TTL | Invalidation is hard, because a new event can belong to many cached queries; use TTLs plus tag-based invalidation |
| Great | The above, plus Elasticsearch's own caches (query cache for filters, request cache for whole responses) and **CDN** caching of search responses | CDN caching only works if results aren't personalized; still has to be invalidated when new events appear |

---

## What "good enough" looks like (from the source's level guide)

- **Mid-level:** clear API and data model, a working view and book flow, no double booking
  using at least status + expiry + cron.
- **Senior:** moves quickly through the high-level design; distributed lock for holds, search
  engine, a plan for popular events, and sharding/replication when asked.
- **Staff+:** goes deep on 2-3 areas without prompting and brings real operational trade-offs.
