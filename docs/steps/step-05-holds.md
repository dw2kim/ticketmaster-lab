# Step 5: holds

**Design link:** `docs/design/05-deep-dives.md` section 2, `docs/design/03-api.md`
**Idea in one sentence:** hold a seat for the buyer while they pay, and let the hold run out
on its own if they walk away.

## What we built

Booking is now two calls (the old one-call `POST /bookings/{eventId}` still works):

| Endpoint | Does |
| --- | --- |
| `POST /bookings/reserve` `{"eventId":1,"ticketIds":[1]}` | Hold the seats. 201 `{bookingId, expiresAt}`, or 409 if held/sold |
| `POST /bookings/{bookingId}/confirm` | Fake payment, then sell. 200 `{status: confirmed}`, or 409 if the hold ran out |

Both need header `X-User-Id`.

How a hold works (`PostgresHolds`, was `HoldService`; "implicit expiry"):

```sql
-- reserve: one statement; an expired hold counts as free
UPDATE tickets
   SET status = 'reserved', reserved_until = now() + 10 min, booking_id = :me
 WHERE id IN (...)
   AND (status = 'available' OR (status = 'reserved' AND reserved_until < now()));
```

- **Nobody releases a hold.** When `reserved_until` passes, the seat simply counts as free:
  for `reserve` (the `WHERE` above) and for the seat map (`GET /events/1` shows it as
  `available`). No cron job is needed for correctness.
- **Confirm sells all seats or none.** It locks the booking's still-valid holds. If even one
  ran out, the booking becomes `expired`, any seats it still holds go back on sale, and the
  buyer gets 409 ("payment would be refunded").
- **Confirm twice is safe.** An already-confirmed booking answers "confirmed" again and
  changes nothing. Real payment providers retry, so this matters.
- Both calls are short transactions. Nothing stays open while the buyer types.

New files: `V4__holds.sql` (`tickets.reserved_until`, `bookings.seat_count`),
`HoldService` (now `PostgresHolds`), `HoldController`, `HoldTest`, `load/reserve.js`, `load/holds.sql`.
Setting: `booking.hold-duration` (default `PT10M`).

## How to run it

Terminal 1, with a **30 second** hold so you can watch it expire:

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--booking.hold-duration=PT30S
```

Terminal 2. Ana holds seat A1:

```bash
curl -s -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ana' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

Ben tries the same seat (409):

```bash
curl -i -X POST localhost:8080/bookings/reserve -H 'X-User-Id: ben' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

Look at the seat map now, and again after 30 seconds (`reserved`, then `available`):

```bash
curl -s localhost:8080/events/1
```

After 30 seconds, Ana tries to pay (409, hold expired). Use the `bookingId` she got:

```bash
curl -i -X POST localhost:8080/bookings/1/confirm -H 'X-User-Id: ana'
```

Now Ben reserves (repeat his command above), then pays with his `bookingId`. Run the pay
command twice: same answer both times.

```bash
curl -s -X POST localhost:8080/bookings/<bookingId>/confirm -H 'X-User-Id: ben'
```

The 200-buyer rush on reserve:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/reset.sql
```

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/reserve.js
```

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/holds.sql
```

## Numbers

Watching a hold (30 second hold), measured on the lab machine:

| Moment | A1 on the seat map | Result |
| --- | --- | --- |
| Ana reserves | reserved | 201 |
| Ben reserves A1 | reserved | 409 |
| 31 s later | available | (no job ran) |
| Ana confirms | available | 409, booking `expired` |
| Ben reserves + confirms | booked | 201, then 200 `confirmed` |
| Ben confirms again | booked | 200 `confirmed` (no change) |

200 buyers reserving at once, 20 seats, k6 (`load/reserve.js`), 3 runs:

| Run | Got a hold | Told no | Held seats in DB | p50 | p95 |
| --- | --- | --- | --- | --- | --- |
| 1 | 20 | 180 | 20 | 107 ms | 300 ms |
| 2 | 20 | 180 | 20 | 91 ms | 177 ms |
| 3 | 20 | 180 | 20 | 82 ms | 191 ms |

`HoldTest` (7 tests) covers: reserve then confirm, held seat blocks others, confirm twice,
expired hold taken by someone else, expired hold not sold, multi-seat all-or-nothing, and
200 threads reserving at once (exactly 20 holds).

## What surprised us

- Expiry needs no code that runs "when time is up". The rule `reserved_until < now()` is
  checked by whoever looks next.
- The raw `tickets` table can say `reserved` for a seat that is really free. Read it through
  the same rule (the seat map does), or add an optional cleanup job later.
- The hard part is not the hold; it is confirm: late payments and half-valid multi-seat
  bookings.

## Quiz (multiple choice)

### Q1. Ana's 10 minute hold on A1 ran out 2 minutes ago. Nobody touched A1 since. What does `tickets.status` say for A1?

- A) `available`, because a job released it.
- B) `reserved`, but every reader treats it as free because `reserved_until` is in the past.
- C) `booked`, because Ana started paying.
- D) It depends on whether the app was restarted.

<details><summary>Answer</summary>

**B.** Nothing rewrites the row when time passes. The rule "reserved and expired = free" is
applied when someone reserves or reads the seat map. There is no release job (not A). Ana
never confirmed (not C). Restarts change nothing; the rule is in the SQL (not D).
</details>

### Q2. Why do reserve and confirm each use their own short transaction, instead of one transaction from reserve until payment?

- A) Spring does not allow long transactions.
- B) A transaction open for minutes holds locks and a connection while the buyer types; with many buyers the database runs out of both.
- C) Short transactions are always faster to write.
- D) Postgres forgets data after 1 minute.

<details><summary>Answer</summary>

**B.** That is the "bad" option in the design: long database locks. The hold lives in the
data (`reserved_until`), not in an open transaction. Spring allows long transactions (not A).
C is not the reason. D is false.
</details>

### Q3. The payment provider calls confirm twice for the same booking (a retry). What happens?

- A) The seat is sold twice.
- B) The second call fails with 500.
- C) The second call sees the booking is already `confirmed` and answers the same, changing nothing.
- D) The booking is cancelled.

<details><summary>Answer</summary>

**C.** Confirm is idempotent: it locks the booking row, sees `confirmed`, and returns.
A seat is one row, so it cannot be sold twice (not A). No error (not B), no cancel (not D).
</details>

## What to understand

- A hold is data with an end time (`reserved_until`), not an open transaction or a lock.
- "Expired" is computed by the same rule everywhere: reserve, seat map, confirm. No job is
  needed for correctness.
- Confirm is the tricky part: all seats or none, and safe to call twice.
