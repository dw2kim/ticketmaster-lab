# Step 2: the naive app

**Design link:** `docs/design/04-high-level-design.md` (view, search, simple booking)
**Idea in one sentence:** build the simplest app that works when buyers come one at a time.

## What we built

- One Spring Boot app, three packages: `event`, `search`, `booking`.
- Three tables (`V1__events_tickets_bookings.sql`): `events`, `tickets`, `bookings`.
  Venue and performer are plain text columns on `events` for now (the design has them as
  their own tables; we split them only if a step needs it).
- Seed data (`V2__seed.sql`): event 1 "Lab Concert" with **20 seats** (A1-A20), event 2
  with 5 seats.
- Three endpoints:

| Endpoint | Does |
| --- | --- |
| `GET /events/{id}` | Event + all its tickets (for the seat map) |
| `GET /events/search?keyword=...` | `ILIKE '%keyword%'` on name, description, performer |
| `POST /bookings/{eventId}` + header `X-User-Id` + `{"ticketIds":[...]}` | Naive booking |

The booking (`NaiveBookingService`) is step 1's look-decide-write, with no transaction and no
lock:

```
1. look:   SELECT status FROM tickets WHERE id IN (...)
2. decide: all "available"?  no -> 409 Conflict
   ---- the gap ----
3. write:  INSERT INTO bookings ...;  UPDATE tickets SET status = 'booked' ...
```

No payment yet. A fake payment comes in step 5.

## How to run it

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run
```

In a second terminal:

```bash
curl -s localhost:8080/events/1
```

```bash
curl -s "localhost:8080/events/search?keyword=jazz"
```

```bash
curl -i -X POST localhost:8080/bookings/1 -H 'X-User-Id: ana' -H 'Content-Type: application/json' -d '{"ticketIds":[1]}'
```

Run the same booking again with `ben`: you get `409`. Seat A1 is taken.

Tests (start their own Postgres in Docker):

```bash
./mvnw test
```

Reset the database when done:

```bash
docker compose down -v
```

## Numbers

Buyers one at a time (from `NaiveAppTest`):

| Variant | Buyers | Tickets | Sold | Rejected |
| --- | --- | --- | --- | --- |
| naive, one at a time | 21 | 20 | 20 | 1 |

Correct. Step 3 sends the buyers at the same moment.

## Quiz (multiple choice)

### Q1. All 6 tests pass. What does that prove?

- A) The app never sells a seat twice.
- B) The app is correct when buyers come one at a time, and nothing more.
- C) The app is ready for 200 buyers.
- D) The database prevents double booking.

<details><summary>Answer</summary>

**B.** Every test sends one request, waits, then sends the next. Nobody is ever in the gap,
so the race cannot show up. A and C claim things no test checked. D is false: nothing in the
schema or the SQL stops two buyers from both writing `booked`.
</details>

### Q2. Ben tries seat A1 after Ana booked it. Why does he get 409?

- A) Postgres locked the row for Ana.
- B) Ben's "look" ran after Ana's "write", so he saw `booked`.
- C) The `UPDATE` failed because the seat was already booked.
- D) Spring rejects duplicate requests.

<details><summary>Answer</summary>

**B.** Pure timing. There is no lock (not A), and the `UPDATE` has no
`AND status = 'available'`, so it would happily overwrite (not C). Spring does nothing like D.
If Ben's look had run *before* Ana's write, he would have "won" too.
</details>

### Q3. Why put venue and performer as columns instead of their own tables, when the design has them as entities?

- A) The design is wrong.
- B) Postgres cannot join tables quickly.
- C) Keep the app tiny: no step needs them as tables yet, and the lesson is on `tickets`.
- D) Spring Boot needs one table per controller.

<details><summary>Answer</summary>

**C.** Our rule is one idea per step. The contended row is the ticket; venue and performer
are read-only details. The design is not wrong (not A); we just do not need its full shape
yet. B and D are false.
</details>

## What to understand

- The naive booking is look, decide, write, in three separate statements, with a gap between them.
- One-at-a-time tests pass, and passing tells you nothing about concurrency.
- The `UPDATE` has no condition on `status`, so the database will not save us. Step 3 shows
  what that costs.
