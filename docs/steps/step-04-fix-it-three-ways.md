# Step 4: fix it three ways

**Design link:** `docs/design/05-deep-dives.md` section 1
**Idea in one sentence:** close the gap between look and write inside the database, three
different ways, and compare.

## What we built

One interface, `BookingService`, with four implementations. Pick one at startup with
`booking.strategy` (default: `conditional`, see ADR-0003).

| Strategy | Class | How it closes the gap |
| --- | --- | --- |
| `naive` | `NaiveBookingService` | It does not (step 2). Kept for comparison. |
| `row-lock` | `RowLockBookingService` | `SELECT ... FOR UPDATE` in a transaction. Other buyers of that seat **wait** until we commit, then see `booked`. |
| `occ` | `OccBookingService` | Read the seat's `version`. Write with `WHERE version = :seen`. If someone wrote first, 0 rows change: roll back, 409. Nobody waits. |
| `conditional` | `ConditionalUpdateBookingService` | No separate look. One `UPDATE ... WHERE status = 'available'`. Check rows changed. The database checks and writes in one step. |

The core of each fix:

```sql
-- row-lock
SELECT status FROM tickets WHERE id IN (...) ORDER BY id FOR UPDATE;   -- others wait here

-- occ
UPDATE tickets SET status = 'booked', version = version + 1
 WHERE id = :id AND version = :seenVersion;                            -- 0 rows = you lost

-- conditional
UPDATE tickets SET status = 'booked'
 WHERE id IN (...) AND status = 'available';                           -- rows changed = seats you got
```

Also new:

- `V3__ticket_version.sql`: adds `tickets.version` (for OCC).
- `ConcurrentBookingTest`: 200 virtual threads, 10 buyers per seat, all released at once.
  For each fix it asserts exactly 20 "yes", 180 "no", 20 booking rows, 0 cheated. We checked
  that this test **fails** on `naive` (33 "yes" for 20 seats).

## How to run it

Terminal 1, pick a strategy:

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--booking.strategy=row-lock
```

(Use `naive`, `row-lock`, `occ` or `conditional`. Stop with Ctrl+C before switching.)

Terminal 2:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/reset.sql
```

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/buy.js
```

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/count.sql
```

Tests:

```bash
./mvnw test
```

## Numbers

200 buyers, 20 seats, k6 (`load/buy.js`), after one warm-up run. 3 runs per strategy.
p50/p95 cover both requests a buyer makes (seat map + booking).

| Strategy | Told "you got it" (3 runs) | Seats booked | Cheated | p50 (ms) | p95 (ms) |
| --- | --- | --- | --- | --- | --- |
| naive | 28 / 34 / 28 | 20 | 8 / 14 / 8 | 88 / 45 / 58 | 194 / 76 / 127 |
| row-lock | 20 / 20 / 20 | 20 | 0 | 106 / 88 / 60 | 222 / 177 / 117 |
| occ | 20 / 20 / 20 | 20 | 0 | 86 / 72 / 53 | 158 / 169 / 114 |
| conditional | 20 / 20 / 20 | 20 | 0 | 60 / 69 / 54 | 116 / 133 / 87 |

## What surprised us

- All three fixes are correct every time. The difference is cost, not correctness.
- At 200 buyers the timings are close and noisy: run-to-run changes are as big as
  strategy-to-strategy changes. Do not pick a winner on these timings alone.
- `conditional` is also the **shortest code**: no separate look, so there is no gap to defend.
- OCC still has the gap. It just notices when someone used it, and undoes our work.

## Quiz (multiple choice)

### Q1. With `row-lock`, 10 buyers want seat A5 at the same moment. What happens to buyers 2 to 10?

- A) They all book A5 too.
- B) They wait at `SELECT ... FOR UPDATE` until buyer 1 commits, then read `booked` and get 409.
- C) They get an error immediately, without waiting.
- D) They are given a different free seat.

<details><summary>Answer</summary>

**B.** `FOR UPDATE` makes them queue on the row lock. After buyer 1 commits, each one reads
the new status, `booked`. A is what naive did. C is how OCC behaves, not row-lock. D: our code
never picks seats for the buyer.
</details>

### Q2. In `occ`, the `UPDATE ... WHERE version = :seen` changes 0 rows. What does that mean?

- A) The seat does not exist.
- B) The database is down.
- C) Someone else wrote to that seat after we read it, so the version moved on.
- D) The booking succeeded.

<details><summary>Answer</summary>

**C.** The version only changes when someone writes. 0 rows means our snapshot is stale: we
lost the race, so we roll back (our booking row disappears) and return 409. We already
checked the seat exists (not A). A down database throws an error, not 0 rows (not B).
</details>

### Q3. Why is `conditional` our default?

- A) It is the only one that is correct.
- B) It is correct, has no separate look (so no gap), holds the lock for one statement only, and is the shortest code.
- C) It never returns 409.
- D) Postgres does not support `FOR UPDATE`.

<details><summary>Answer</summary>

**B.** All three fixes were correct (not A). Every fix returns 409 to the 180 losers (not C).
Postgres supports `FOR UPDATE` fine; we used it (not D). The deciding points are simplicity
and the shortest lock time. See ADR-0003.
</details>

## What to understand

- The fix must live in the database: a lock (`FOR UPDATE`), a version check (OCC), or one
  conditional `UPDATE`. All three sold exactly 20, with 0 cheated buyers.
- Always check "rows changed" after a conditional write. That number is the database telling
  you whether you won.
- A test with 200 threads and a start latch can see the race. We proved it by running the
  test against `naive` and watching it fail.
