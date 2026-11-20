# Step 3: break it

**Design link:** `docs/design/05-deep-dives.md` section 1
**Idea in one sentence:** send 200 buyers at the same moment and count how many were told
"you got it" for 20 seats.

## What we built

No app changes. Only a load test and two SQL files:

- `load/buy.js` (k6): 200 buyers start at once. Each one looks at the seat map, picks a
  random seat that looks free, and tries to book it. Exactly what a real person does.
- `load/count.sql`: after the rush, compares promises with seats.
- `load/reset.sql`: puts all seats back on sale, so you can run it again.

## How to run it

Terminal 1:

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run
```

Terminal 2, from the `ticketmaster-lab` folder (Mac):

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/buy.js
```

Look for `buyers_told_you_got_it` in the output. Then count:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/count.sql
```

Run it again (reset first):

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/reset.sql
```

Note: k6 reports `http_req_failed` around 30%. Those are the 409 "sold out" answers. k6
counts any 4xx as failed; here they are the correct answer.

## Numbers

Measured on the lab machine, naive booking, 3 runs:

| Run | Buyers | Seats | Told "you got it" | Seats booked | Cheated buyers | p95 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 200 | 20 | 42 | 20 | 22 | 242 ms |
| 2 | 200 | 20 | 30 | 20 | 10 | 202 ms |
| 3 | 200 | 20 | 28 | 20 | 8 | 154 ms |

"Cheated" = told "you got it", but the seat's `booking_id` now points at someone else's
booking. The last writer wins; earlier winners are silently overwritten.

Your numbers will differ. Any number above 20 in "Told you got it" is the bug.

## What surprised us

- Nothing crashed and no error was logged. Every request "succeeded".
- The database still shows exactly 20 booked seats. Looking only at `tickets`, everything
  looks fine. The damage is in `bookings`: more promises than seats.
- Run 1 was worst. The app was cold (first requests are slower), so the gap between look and
  write was wider.

## Quiz (multiple choice)

### Q1. `tickets` shows exactly 20 booked seats. Why is that NOT proof that we are fine?

- A) Because Postgres miscounts under load.
- B) Because each `UPDATE` overwrites the seat's owner, so the seat count can never go above 20, while extra buyers were still told "you got it".
- C) Because some seats were booked twice in the `tickets` table.
- D) Because k6 retried some requests.

<details><summary>Answer</summary>

**B.** A seat is one row. Two buyers writing it just leaves the last one as owner. The
overselling shows up as extra rows in `bookings` (promises), not extra seats. Postgres counts
fine (not A). One row cannot be "booked twice" (not C). k6 does not retry here (not D).
</details>

### Q2. Why did the first run oversell the most?

- A) The first run had more buyers.
- B) Random luck only.
- C) The app was cold, so each request was slower and the gap between look and write was wider.
- D) The database was empty.

<details><summary>Answer</summary>

**C.** A wider gap means more buyers land in it. Same 200 buyers every run (not A). Luck
plays a part, but the pattern repeats when the app starts cold (not only B). The seed data
was there (not D). Lesson: a slower system makes this race worse, not better.
</details>

### Q3. Which change would fix this for real?

- A) Add more app servers.
- B) Make the client wait 1 second before booking.
- C) Make the database refuse the second write: e.g. `UPDATE ... WHERE id = ? AND status = 'available'` and check how many rows changed.
- D) Log a warning when a seat is booked twice.

<details><summary>Answer</summary>

**C.** The check and the write become one statement the database does all at once, so only
one buyer can change the row. More servers (A) make the race worse. Waiting (B) moves the gap,
it does not close it. Logging (D) only tells you after a customer was cheated. Step 4 tries C
and two other fixes.
</details>

## What to understand

- Under real concurrency the naive booking oversells: 28 to 42 "you got it" answers for 20 seats.
- The damage is silent: no errors, and the `tickets` table looks correct. You must count promises.
- The fix has to close the gap between look and write inside the database. That is step 4.
