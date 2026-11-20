# Step 6: flash sale and a waiting room

**Design link:** `docs/design/05-deep-dives.md` section 4 ("Great: virtual waiting queue")
**Idea in one sentence:** when 10,000 people arrive at once, let them in a few at a time, in
arrival order, so the booking path stays fast.

## What we built

A waiting room in front of booking, in Redis. Turned on with `queue.enabled=true`.

| Endpoint | Does |
| --- | --- |
| `POST /events/{id}/queue` | Join the line (once; joining again keeps your place) |
| `GET /events/{id}/queue` | `{admitted, position, estimatedWaitSeconds}` |
| `POST /bookings/reserve` | Now **403** unless you were let in |

How it works (`WaitingRoom`, `QueueAdmitter`):

```
join:    INCR queue:1:seq                      -> your arrival number
         ZADD queue:1 NX <arrival> <userId>    -> sorted set = fair order
where:   ZRANK queue:1 <userId> + 1            -> your position
admit:   every second: ZPOPMIN queue:1 <N>     -> the next N people
         SET admitted:1:<userId> 1 EX 600      -> "you may book", expires by itself
book:    no admitted:1:<userId> key?           -> 403
```

- **Fair order:** the sorted set is scored by arrival number, so first come, first in.
- **One key per admitted person**, not one big set, because a key can expire and a set
  member cannot (same lesson as step 5b's sorted set).
- **Polling, not SSE:** clients ask "where am I?" every 1-5 seconds, as long as the server
  suggests. The design pushes updates with SSE; polling keeps the lab small (ADR-0006).
- Settings: `queue.admit-per-second` (default 50), `queue.admitted-for` (default 10 min).

New files: `queue/WaitingRoom`, `queue/QueueAdmitter`, `queue/QueueController`,
`WaitingRoomTest` (4 tests), `load/spike.js`.

## How to run it

**A. Feel the waiting room by hand.** Terminal 1 (let in 1 person per second):

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--queue.enabled=true --queue.admit-per-second=1"
```

Terminal 2. Reserving without queueing is refused (403):

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ana' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

Ana and Ben join:

```bash
curl -s -X POST localhost:8080/events/1/queue -H 'X-User-Id: ana'
```

```bash
curl -s -X POST localhost:8080/events/1/queue -H 'X-User-Id: ben'
```

A second later, ask again. `admitted` turns `true`. Then reserve works (201):

```bash
curl -s localhost:8080/events/1/queue -H 'X-User-Id: ana'
```

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ana' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

Peek into Redis:

```bash
docker compose exec redis redis-cli ZRANGE queue:1 0 -1 WITHSCORES
```

```bash
docker compose exec redis redis-cli TTL admitted:1:ana
```

**B. The 10,000-buyer spike.** Reset first, each time:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/reset.sql
```

```bash
docker compose exec redis redis-cli FLUSHALL
```

Without the waiting room (start the app normally, `./mvnw spring-boot:run`):

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e QUEUE=0 grafana/k6 run - < load/spike.js
```

With the waiting room (restart the app with `--queue.enabled=true --queue.admit-per-second=100`):

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e QUEUE=1 grafana/k6 run - < load/spike.js
```

k6 needs memory for 10,000 virtual buyers. If your Mac struggles, add `-e BUYERS=5000`.

## Numbers

10,000 buyers, 20 seats, `load/spike.js`, lab machine (4 CPUs), 2 runs each.
"Booking path" = seat map + reserve, measured **after** any waiting.

| | No waiting room | Waiting room (100/s) |
| --- | --- | --- |
| Got a hold | 20 / 20 | 20 / 20 |
| Told no | 8,172 / 8,172 | 8,143 / 8,130 |
| Errors (timeouts, refused) | 1,808 / 1,808 | 1,837 / 1,850 |
| ... of which on the booking path | 1,808 | **0** |
| Booking path p50 | 4.4 s / 5.3 s | **2 ms / 2 ms** |
| Booking path p95 | 30 s / 30 s (timeout) | **9 ms / 4 ms** |
| Time in the waiting room p50 / p95 | - | 41 s / 79 s |
| Whole run | about 60 s | 86 s |

Correct in every run: exactly 20 seats held, never more.

## What surprised us

- **The waiting room did not reduce errors.** All ~1,840 errors in the queued runs were on
  `POST /events/1/queue`, in the first second: 10,000 connections hit one app at once, and
  the operating system refused the overflow. The waiting room protects what is **behind**
  it, not itself. That is why real systems run it at the edge (CDN or a separate, very
  simple service), not inside the booking app.
- Behind the waiting room, booking became calm: p95 went from 30 s (timeouts) to under
  10 ms. Each buyer who gets in gets an answer at once.
- The price is waiting: half the buyers waited over 40 s, mostly to learn "sold out". A
  real waiting room would also tell the line when the event is sold out.
- Polling is load too: about 80,000 requests in 86 s, almost all cheap Redis reads. SSE
  would push instead.

## Quiz (multiple choice)

### Q1. With the waiting room on, Ben calls `POST /bookings/reserve` without joining the queue. What happens?

- A) 201, the waiting room is only a suggestion.
- B) 403: reserve checks for his `admitted:1:ben` key first, and there is none.
- C) He is added to the queue automatically.
- D) 409, the seat is taken.

<details><summary>Answer</summary>

**B.** The gate is in the booking path, so the queue cannot be skipped. Without it, the
waiting room would only be a suggestion (A). Nobody is queued automatically (C). The seat is
not even checked (D).
</details>

### Q2. Why one key per admitted person (`admitted:1:ana`) instead of one set `admitted:1` with all names?

- A) Sets are slower.
- B) A key can expire by itself (TTL); a set member cannot, so people would stay admitted forever.
- C) Redis sets cannot hold strings.
- D) It uses less memory.

<details><summary>Answer</summary>

**B.** Same lesson as step 5b's sorted set: expiry belongs to keys, not members. A, C and D
are not the reason.
</details>

### Q3. In the queued run, the 1,800 errors all happened when joining the queue. What does that tell us?

- A) The waiting room is broken.
- B) Redis is too slow.
- C) The waiting room protects what is behind it, but the front door itself must take the first rush: it belongs at the edge, in front of the app.
- D) k6 is broken.

<details><summary>Answer</summary>

**C.** Everything behind the queue was calm (0 errors, p95 under 10 ms). The rush hit the
join request because it runs in the same app. Redis answered in milliseconds (not B).
Nothing is broken (not A or D); this is the real limit of a waiting room inside the app.
</details>

## What to understand

- A waiting room turns "10,000 at once" into "100 per second". Booking stayed fast (p95 under
  10 ms instead of 30 s timeouts) and fair (arrival order).
- The gate must be in the booking path (403 if not admitted), and admissions must expire.
- The waiting room protects what is behind it, not itself. The first rush still lands
  somewhere; real systems put that somewhere at the edge.
