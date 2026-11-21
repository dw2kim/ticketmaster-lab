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
- Settings: `queue.admit-per-second` (people let in per tick, default 50), `queue.tick`
  (default 1 s; 30 s makes the waiting easy to watch by hand), `queue.admitted-for` (default 10 min).

New files: `queue/WaitingRoom`, `queue/QueueAdmitter`, `queue/QueueController`,
`WaitingRoomTest` (4 tests), `load/spike.js`.

## How to run it

You need **two terminal windows**, both in the `ticketmaster-lab` folder:

- **Terminal 1 runs the app.** It stays busy and prints the app's log. Stop the app there
  with `Ctrl+C`.
- **Terminal 2 is where you type everything else** (curl, redis-cli, k6).

### 0. Once, before you start

In **Terminal 1**:

1. Go to the project folder (change the path if you cloned it somewhere else):

```bash
cd ~/ticketmaster-lab
```

2. Get this step's code:

```bash
git fetch origin && git checkout step-06-waiting-room && git pull
```

3. Check Java. It must say `25`:

```bash
java -version
```

4. Make sure Docker Desktop is running (whale icon in the menu bar). Then start Postgres and
   Redis:

```bash
docker compose up -d
```

5. Check both are up. You should see `postgres` and `redis`, both `running`:

```bash
docker compose ps
```

Open **Terminal 2** (`Cmd+T` in Terminal opens a new tab) and go to the same folder:

```bash
cd ~/ticketmaster-lab
```

### Part A: feel the waiting room by hand

**Terminal 1.** Start the app with the waiting room on, letting in **1 person every 30
seconds** (slow on purpose, so you have time to type):

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--queue.enabled=true --queue.admit-per-second=1 --queue.tick=30s"
```

Wait for this line in the log. The app is ready when you see it:

```
Started TicketmasterApplication in 3.8 seconds
```

**Terminal 2.** Type each command; compare with "you should see".

0. Start clean: all seats free, the line empty. Earlier steps left seats taken, and a taken
   seat gives `409` even when you were let in.

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/reset.sql
```

```bash
docker compose exec redis redis-cli FLUSHALL
```

1. Reserve without queueing.

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ana' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

You should see `403` and `"message":"waiting room is on: you have not been let in yet ..."`.

2. Ana and Ben join the line, one right after the other (one command, so no tick falls
   between them):

```bash
curl -s -X POST localhost:8080/events/1/queue -H 'X-User-Id: ana'; echo; curl -s -X POST localhost:8080/events/1/queue -H 'X-User-Id: ben'; echo
```

You should see two lines: Ana `"position":1`, Ben `"position":2`. The
`estimatedWaitSeconds` depend on when the next tick comes (at most 30 and 60).

3. (Step 2 already added Ben.)

4. Look at the line inside Redis: names with their arrival numbers, Ana first.

```bash
docker compose exec redis redis-cli ZRANGE queue:1 0 -1 WITHSCORES
```

5. Ben tries to skip the line:

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ben' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[2]}'
```

You should see `403`. He is in the line, but not let in yet.

6. Wait until Ana's estimate has passed (at most 30 seconds). Ask where Ana and Ben are:

```bash
curl -s localhost:8080/events/1/queue -H 'X-User-Id: ana'
```

```bash
curl -s localhost:8080/events/1/queue -H 'X-User-Id: ben'
```

You should see Ana `{"admitted":true,...}`, and Ben `{"admitted":false,"position":1,...}`.
He moved up.

7. Ana reserves:

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ana' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

You should see `{"bookingId":...,"expiresAt":...}` (201).

8. See how long Ana may book before she must queue again (about 600 seconds):

```bash
docker compose exec redis redis-cli TTL admitted:1:ana
```

9. Wait another 30 seconds and repeat Ben's command from step 6 (`"admitted":true`), then from
   step 5 (now he gets a `bookingId`).

If someone shows `"admitted":true` sooner than you expected, a tick happened while you were
typing; the line moved on without you watching. Everything still works. If a reserve gives
`409 seat already held or booked`, the seat is taken: run the two reset commands in step 0.

Done with Part A: go to **Terminal 1** and press `Ctrl+C` to stop the app.

### Part B: the 10,000-buyer spike

This part compares two runs. Each run has the same three moves: (1) start the app in
Terminal 1, (2) reset in Terminal 2, (3) run k6 in Terminal 2.

**Run 1: no waiting room.**

1. **Terminal 1.** Start the app normally, then wait for `Started TicketmasterApplication`:

```bash
./mvnw spring-boot:run
```

2. **Terminal 2.** Reset: all seats free, Redis empty.

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/reset.sql
```

```bash
docker compose exec redis redis-cli FLUSHALL
```

3. **Terminal 2.** Send 10,000 buyers. It takes about a minute and prints a summary at the end:

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e QUEUE=0 grafana/k6 run - < load/spike.js
```

In the summary, look at these lines:

| Line | Means |
| --- | --- |
| `buyers_got_a_hold` | must be 20 |
| `buyers_got_error` | buyers who got a timeout or a refused connection |
| `booking_path_ms` | how long seat map + reserve took (p50, p95) |

4. **Terminal 1.** Press `Ctrl+C` to stop the app.

**Run 2: with the waiting room** (100 people let in per second).

1. **Terminal 1.** Start the app, then wait for `Started TicketmasterApplication`:

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--queue.enabled=true --queue.admit-per-second=100"
```

2. **Terminal 2.** Reset again (the same two commands as in run 1).

3. **Terminal 2.** Send 10,000 buyers through the queue. It takes about 90 seconds:

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e QUEUE=1 grafana/k6 run - < load/spike.js
```

Look at the same lines, plus `queue_wait_s` (seconds spent in line). Compare
`booking_path_ms` and `buyers_got_error` with run 1.

4. **Terminal 1.** `Ctrl+C`.

k6 needs a lot of memory for 10,000 buyers. If your Mac struggles or k6 crashes, add
`-e BUYERS=5000` to the k6 command.

### When you are done

**Terminal 2.** Stop Postgres and Redis and wipe their data:

```bash
docker compose down -v
```

### If something goes wrong

| You see | Why | Fix |
| --- | --- | --- |
| `curl: (7) Failed to connect` | The app is not running | Start it in Terminal 1, wait for `Started` |
| `404` on `/events/1/queue` | Old code: wrong branch | `git checkout step-06-waiting-room`, restart the app |
| `403` on reserve | Waiting room is on and you were not let in | Join, then wait until `admitted` is `true` |
| `409` on reserve after being let in | The seat is taken from an earlier try | Run the two reset commands (Part A, step 0) |
| `release version 25 not supported` | Java is not 25 | See `java -version`; fix `JAVA_HOME` |
| `no configuration file provided` | Not in the project folder | `cd ~/ticketmaster-lab` |
| k6 shows `connection refused` for every request | App not running, or wrong `BASE_URL` | Check Terminal 1; on a Mac use `host.docker.internal` |

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
