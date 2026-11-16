# 05: Deep dives

Each deep dive starts from a non-functional requirement. The options are summarized in our
own words. In the lab we **run** them, not just discuss them.

---

## §1 No double booking under contention (steps 3–4)

**Problem.** Two buyers read "seat 12 is available" at the same moment, and both write
"sold". This is a read-check-write race.

**Options we will run and compare:**

| Option | Idea | Trade-off |
| --- | --- | --- |
| Pessimistic row lock | `SELECT … FOR UPDATE` then update | Simple and correct; buyers queue on hot rows |
| Optimistic concurrency (OCC) | `UPDATE … WHERE id=? AND version=?`; retry or fail on 0 rows | No waiting; many failures when contention is high |
| Atomic conditional update | `UPDATE tickets SET status='booked' WHERE id=? AND status='available'` and check the row count | Smallest; the database does the check-and-set in one statement |

Isolation level matters too. Also try running the naive version under `SERIALIZABLE`.

**Measure:** sold count (must equal the number of tickets), rejected count, throughput,
p50/p95 latency.

---

## §2 Reserving tickets during checkout (step 5)

**Problem.** No one should spend 5 minutes on a payment form and then lose the seat. Hold it
for the buyer, release it if they leave, and mark it sold if they pay.

| Rating | Option | Why |
| --- | --- | --- |
| Bad | Hold a DB lock for the whole checkout | Long transactions, no timeout semantics, connections pile up |
| Good | `status=reserved` + `reserved_until`; a cron job releases expired holds | Works; seats stay stuck between cron runs and cron is one more moving part |
| Great | **Implicit expiry**: treat a ticket as available if `status='available' OR (status='reserved' AND reserved_until < now())` | No stuck seats, no cron needed for correctness (cleanup is optional) |
| Great | **Redis lock with TTL**: `SET ticket:{id} {userId} NX EX 600` | Auto-expires and keeps holds off the DB; if Redis loses the lock, the DB transaction at confirm time is still the final guard |

The design picks the Redis lock. The lab builds **both** greats and compares them.

**Booking flow with holds:** reserve → lock with 10-minute TTL + booking `in_progress` →
pay → webhook → one transaction sets ticket `booked` + booking `confirmed` (idempotent).

---

## §3 Scaling the view API for popular events (step 7, proposed)

**Problem.** Everyone keeps refreshing the same event page.

**Option:** cache event, venue and performer data in Redis (it rarely changes, so a long TTL
or invalidate on write), scale the services horizontally behind a load balancer, and put a
CDN at the edge. Seat availability is the part that changes, so cache it only briefly or
serve it separately.

**Measure:** requests/sec and p95 with and without the cache; DB queries per request.

---

## §4 High-demand events: millions booking at once (step 6)

**Problem.** The seat map goes stale in seconds, and everyone clicks seats that are already
gone.

| Rating | Option | Why |
| --- | --- | --- |
| Good | Push seat changes to clients (SSE) | A fresher map, but for the biggest events the map still turns red almost instantly |
| Great | **Virtual waiting queue**: put users in a queue (e.g. Redis sorted set by arrival), let N in at a time, notify them when it's their turn (WebSocket/SSE) | Protects the booking path and gives a fair, calm experience |

The lab builds the waiting room and runs a ~10,000-buyer spike with and without it.

---

## §5 Low-latency search (step 8, proposed)

**Problem.** `LIKE '%Taylor%'` scans the whole table.

| Rating | Option |
| --- | --- |
| Good | B-tree indexes on filter columns (date, location, type) and tuned queries |
| Great | Full-text index in the DB (Postgres `tsvector` + GIN) |
| Great | A dedicated search engine (Elasticsearch), kept in sync from Postgres via CDC |

The lab does the Postgres options. Elasticsearch is a stretch goal.

---

## §6 Repeated search queries (step 8, proposed)

**Problem.** Many people run the same search.

| Rating | Option |
| --- | --- |
| Good | Cache query results in Redis, keyed by normalized query params, with a short TTL |
| Great | The above, plus search-engine query caching and edge/CDN caching of popular search responses |

---

## What "good enough" looks like (from the source's level guide)

- **Mid-level:** clear API and data model, a working view and book flow, no double booking
  using at least status + expiry + cron.
- **Senior:** moves quickly through the high-level design; distributed lock for holds, search
  engine, a plan for popular events, and sharding/replication when asked.
- **Staff+:** goes deep on 2–3 areas without prompting and brings real operational trade-offs.
