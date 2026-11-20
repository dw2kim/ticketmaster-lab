# Step 5b: holds in Redis (distributed lock with TTL)

**Design link:** `docs/design/05-deep-dives.md` section 2 ("Great: Redis lock with TTL")
**Idea in one sentence:** keep the hold in Redis, which deletes it by itself when time is up,
while Postgres still decides the sale.

## What we built

Same endpoints as step 5. A switch picks where holds live:
`booking.hold-store=postgres` (step 5, default) or `booking.hold-store=redis` (this step).

| | Step 5: Postgres | Step 5b: Redis |
| --- | --- | --- |
| Hold | `tickets.status = 'reserved'`, `reserved_until` | `SET ticket:{id} {bookingId} NX EX 600` |
| Expiry | "reserved and `reserved_until < now()`" counts as free | Redis deletes the key itself (TTL) |
| `tickets` table while held | `reserved` | still `available` (DB knows nothing) |
| Seat map | reads the table | table + sorted set `event:1:held` scored by expiry time |
| Sale | Postgres | Postgres, still guarded by `WHERE status = 'available'` |

How `RedisHolds` works:

- **reserve:** check the seats are not sold (DB). Then take one lock per seat with
  `SET ... NX EX` (atomic: only one buyer gets it). If any lock fails, give back the ones we
  got. Add the seats to the sorted set, so the seat map shows them as reserved.
- **confirm:** check every lock still holds our `bookingId`. If not, the booking expires
  (409). Then the **database guard**: `UPDATE tickets ... WHERE status = 'available'`. If a
  lock was lost after our check and someone else bought first, this changes 0 rows and we
  expire too. Finally delete our locks.
- **Redis down:** reserve and confirm return 503 "holds unavailable". The seat map still
  works (it shows no holds). No seat can be sold twice, because the sale is in Postgres.

New: `Holds` interface, `RedisHolds`, `HoldStore` (the switch), `PostgresHolds` (was
`HoldService`), `V5__booking_ticket_ids.sql` (the booking remembers its seats),
`RedisHoldTest`, Redis in `compose.yaml` (memory only: a restart forgets all holds).

## How to run it

Terminal 1:

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--booking.hold-store=redis --booking.hold-duration=PT30S"
```

Terminal 2. Start clean (Postgres seats and Redis holds):

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/reset.sql
```

```bash
docker compose exec redis redis-cli FLUSHALL
```

**A. Watch the TTL.** Ana holds A1:

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ana' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

Look inside Redis: the value is Ana's `bookingId`; TTL counts down from 30.

```bash
docker compose exec redis redis-cli GET ticket:1
```

```bash
docker compose exec redis redis-cli TTL ticket:1
```

The table still says `available`, but the seat map says `reserved`:

```bash
docker compose exec postgres psql -U lab -d ticketmaster -c "SELECT id, status FROM tickets WHERE id = 1;"
```

```bash
curl -s localhost:8080/events/1
```

Wait 30 seconds. `GET ticket:1` now returns nothing, the seat map shows `available`, and Ana's
confirm gets 409:

```bash
curl -s -X POST localhost:8080/bookings/<bookingId>/confirm -H 'X-User-Id: ana'
```

**B. Kill Redis mid-sale.** Cara holds A2 (note her `bookingId`):

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: cara' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[2]}'
```

Restart Redis. It keeps nothing, so Cara's hold is gone:

```bash
docker compose restart redis
```

Dan now reserves A2 and pays (use his `bookingId`), then Cara tries to pay (409):

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: dan' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[2]}'
```

```bash
curl -s -X POST localhost:8080/bookings/<danBookingId>/confirm -H 'X-User-Id: dan'
```

```bash
curl -s -X POST localhost:8080/bookings/<caraBookingId>/confirm -H 'X-User-Id: cara'
```

**C. Redis down.** Reserve gets 503; the seat map still answers:

```bash
docker compose stop redis
```

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: eve' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[5]}'
```

```bash
docker compose start redis
```

**D. The 200-buyer rush** (reset both first, as above):

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/reserve.js
```

```bash
docker compose exec redis redis-cli ZCARD event:1:held
```

## Numbers

Demo (30 second hold), measured on the lab machine:

| Moment | Result |
| --- | --- |
| Ana reserves A1 | 201. Redis `ticket:1` = her bookingId, TTL 30 s. Table: `available`. Map: `reserved` |
| Ben reserves A1 | 409 "seat already held" |
| 31 s later | `ticket:1` gone (Redis deleted it). Map: `available` |
| Ana confirms | 409 "hold expired; payment would be refunded" |
| Cara holds A2, Redis restarts | Map shows A2 `available` (hold lost) |
| Dan reserves + confirms A2 | 201, then `confirmed` |
| Cara confirms | 409. A2 sold exactly once |
| Redis stopped, Eve reserves | 503 "holds unavailable: Redis is down". Map still works |

200 buyers reserving at once, 20 seats, k6 (`load/reserve.js`), after a warm-up, 3 runs each:

| Hold store | Got a hold | Told no | Held (Redis zset / DB) | p50 (ms) | p95 (ms) |
| --- | --- | --- | --- | --- | --- |
| redis | 20 / 20 / 20 | 180 | 20 / 0 | 128 / 115 / 62 | 315 / 236 / 150 |
| postgres | 20 / 20 / 20 | 180 | 0 / 20 | 119 / 103 / 57 | 262 / 216 / 173 |

`RedisHoldTest` (7 tests): lock + TTL set and table untouched; held seat blocks others;
confirm sells and releases (twice is safe); expired lock loses; **lost lock caught by the
database**; multi-seat all-or-nothing; 200 threads -> exactly 20 holds. All 23 tests pass.

## What surprised us

- Not faster here. Both stores do the same DB work (check sold seats, insert a booking), and
  at 200 buyers the noise is bigger than the difference. Redis pays off when holds are many
  and the DB is the bottleneck.
- The seat map got harder: the table says `available` for a held seat, so the map needs a
  second source (the sorted set). A plain Redis set would keep "ghost" holds forever.
- Losing Redis does not break correctness, only the experience: holds vanish (step B) or
  nobody can reserve (step C). The database guard from step 4 is what keeps the invariant.

## Quiz (multiple choice)

### Q1. Ana holds A1 in Redis. What does `SELECT status FROM tickets WHERE id = 1` say?

- A) `reserved`
- B) `available`: with Redis holds, the table only knows available or booked.
- C) `booked`
- D) Nothing; the row is locked.

<details><summary>Answer</summary>

**B.** The hold lives only in Redis (`ticket:1`). That is why the seat map needs the sorted
set to show A1 as reserved. A is how step 5 (Postgres holds) looks. Nothing is sold yet
(not C), and no DB lock is held between requests (not D).
</details>

### Q2. Redis restarts and forgets Ana's hold. Ben reserves and buys A1. Could A1 be sold twice?

- A) Yes, if Ana pays at the same moment.
- B) No: the sale is an `UPDATE ... WHERE status = 'available'` in Postgres, so only one sale changes the row.
- C) No, because Redis replays its log on restart.
- D) Yes, Redis is the source of truth for sales.

<details><summary>Answer</summary>

**B.** Redis only holds; Postgres sells, with step 4's conditional update. Our Redis runs
memory only, so nothing is replayed (not C). Redis is never the record of a sale (not D),
and that is exactly why A cannot happen. Ana gets 409 and a refund.
</details>

### Q3. Why a sorted set scored by expiry time for the seat map, not a plain set of held seats?

- A) Sorted sets are faster.
- B) Plain sets cannot hold numbers.
- C) Set members do not expire with the lock keys, so abandoned holds would stay "reserved" forever; with the score we only count holds whose expiry is in the future.
- D) Redis does not support plain sets.

<details><summary>Answer</summary>

**C.** The lock key expires by itself, but a set member does not. The score lets the seat
map ignore expired entries (and trim them lazily). A, B and D are false.
</details>

## What to understand

- `SET key value NX EX seconds` is a lock with a built-in timeout: one atomic command, and
  Redis cleans up by itself.
- Redis holds; Postgres sells. The conditional `UPDATE` from step 4 is the final guard, so a
  lost or expired lock can cost a buyer their seat, but never sells it twice.
- Postgres holds (step 5) and Redis holds (step 5b) were equally correct and about as fast
  here. Redis adds infrastructure and a harder seat map; it pays off at bigger scale.
