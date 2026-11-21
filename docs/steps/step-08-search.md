# Step 8: fast search

**Design link:** `docs/design/05-deep-dives.md` section 5
**Idea in one sentence:** stop reading every event for every search; let an index find the
matching events, and meet the 500 ms goal with 100,000 events.

## What we built

`GET /events/search?keyword=...` can now search two ways. Pick with `search.strategy`
(default `fulltext`):

| Strategy | Query | Finds | Index |
| --- | --- | --- | --- |
| `like` (step 2) | `name ILIKE '%jazz%' OR ...` | any part of a word: `jaz` finds "Jazz" | none: reads every row. Optional: `load/trigram-index.sql` |
| `fulltext` | `search @@ websearch_to_tsquery('english', 'jazz')` | whole words and their forms: `concerts` finds "Concert" | GIN on `events.search` (V6) |

How full-text search works (migration `V6__search_fulltext.sql`):

```sql
to_tsvector('english', 'Golden Sparrows: Neon Nights Concert')
-- 'concert':5 'golden':1 'neon':3 'night':4 'sparrow':2
```

- Each event gets a `search` column: its words, lower-cased and cut to their stem
  ("Nights" -> `night`). Postgres keeps it up to date by itself (`GENERATED ... STORED`).
- A **GIN index** maps each word to the events that contain it. That is an *inverted index*,
  the same idea Elasticsearch is built on.
- `websearch_to_tsquery` understands what people type: `quiet night` (both words),
  `"neon nights"` (phrase), `jazz -quiet` (not "quiet").

The trigram index (`load/trigram-index.sql`, optional) is the other way: keep the `LIKE`
query, but index every 3-letter piece of the text (`spa`, `par`, `arr`, ...). Then `'%spar%'`
is fast too, and word parts still work.

New files: `V6__search_fulltext.sql`, `search/EventSearch`, `SearchTest` (5 tests),
`load/seed-events.sql` (100,000 events), `load/search.js`, `load/explain-search.sql`,
`load/trigram-index.sql`.

## How to run it

Two terminal windows, both in the `ticketmaster-lab` folder. **Terminal 1 runs the app**
(stop it with `Ctrl+C`). **Terminal 2 is for everything else.**

### 0. Once, before you start

**Terminal 1:**

```bash
cd ~/ticketmaster-lab
```

```bash
git fetch origin && git checkout step-08-search && git pull
```

```bash
docker compose up -d
```

**Terminal 2:**

```bash
cd ~/ticketmaster-lab
```

### Part A: what each search finds (2 events)

**Terminal 1.** Start the app (full-text search is the default). Wait for
`Started TicketmasterApplication`:

```bash
./mvnw spring-boot:run
```

**Terminal 2.**

0. Make sure only the 2 seed events exist (removes any events from Part B):

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster -c "DELETE FROM events WHERE id > 2"
```

1. A whole word. You should see `Quiet Jazz Night`:

```bash
curl -s "localhost:8080/events/search?keyword=jazz"
```

2. Part of a word. You should see `[]`: full-text search matches words, not pieces:

```bash
curl -s "localhost:8080/events/search?keyword=jaz"
```

3. Another form of a word. You should see `Lab Concert`: "concerts" and "Concert" share the
   stem `concert`:

```bash
curl -s "localhost:8080/events/search?keyword=concerts"
```

4. Two words. You should see `Quiet Jazz Night`:

```bash
curl -s "localhost:8080/events/search?keyword=quiet%20night"
```

5. See what Postgres stores for a name:

```bash
docker compose exec postgres psql -U lab -d ticketmaster -c "SELECT to_tsvector('english', 'Golden Sparrows: Neon Nights Concert');"
```

You should see `'concert':5 'golden':1 'neon':3 'night':4 'sparrow':2`.

Now compare with `LIKE`. **Terminal 1:** `Ctrl+C`, then:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--search.strategy=like
```

**Terminal 2.** Run steps 2 and 3 again. Now `jaz` finds `Quiet Jazz Night`, and
`concerts` finds `[]`. The opposite of full-text.

Done with Part A: **Terminal 1**, `Ctrl+C`.

### Part B: 100,000 events, 50 people searching

1. **Terminal 2.** Add 100,000 events (takes about a second). You should see `100002`:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/seed-events.sql
```

2. See how Postgres runs each search. Look at the scan type and `Execution Time`:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/explain-search.sql
```

You should see `Seq Scan` (or `Parallel Seq Scan`) for `LIKE` (tens of ms), and
`Bitmap Index Scan on events_search_idx` for full-text (a few ms).

Then three runs. Each run: start the app in Terminal 1, run k6 in Terminal 2, `Ctrl+C` in
Terminal 1.

**Run 1: `LIKE`, no index.**

**Terminal 1:**

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--search.strategy=like
```

**Terminal 2** (50 people searching for 30 s):

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/search.js
```

Look at `http_reqs` (the second number is requests per second) and `http_req_duration` p95.
The goal is under 500 ms.

**Run 2: `LIKE` + trigram index.** Keep the same app running. **Terminal 2:**

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/trigram-index.sql
```

Run the k6 command again. Run `load/explain-search.sql` again: `LIKE` now says
`Bitmap Index Scan on events_name_trgm`. Then remove the trigram index:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster -c "DROP INDEX events_name_trgm, events_description_trgm, events_performer_trgm"
```

**Run 3: full-text.** **Terminal 1:** `Ctrl+C`, then:

```bash
./mvnw spring-boot:run
```

**Terminal 2:** run the k6 command again.

### When you are done

```bash
docker compose down -v
```

(`down -v` wipes the database, including the 100,000 events. To keep the database and only
remove those events: `DELETE FROM events WHERE id > 2`, as in Part A step 0.)

### If something goes wrong

| You see | Why | Fix |
| --- | --- | --- |
| `curl: (7) Failed to connect` | The app is not running | Start it in Terminal 1, wait for `Started` |
| `Connection to localhost:5432 refused` when starting the app | Postgres is not running | `docker compose up -d`, then `docker compose ps` |
| `column "search" does not exist` | Old code: migration V6 has not run | `git checkout step-08-search`, restart the app |
| `jaz` finds nothing | Full-text matches whole words | That is the lesson. Use `like` to match parts |
| Part A shows many events | The 100,000 events are still there | Part A step 0 |

## Numbers

100,000 events (`load/seed-events.sql`), 50 people searching for 30 s (`load/search.js`,
8 keywords from common to no match), after a 10 s warm-up, 2 runs each, lab machine (4 CPUs,
k6 on the same machine). 0 failed requests in all runs.

| Search | Requests/s | p50 | p95 | p99 | One query (`EXPLAIN ANALYZE`, "sparrows") |
| --- | --- | --- | --- | --- | --- |
| `LIKE`, no index | 19.6 / 19.8 | 2.5 s | **2.7 s** | 2.8 s | 74 ms, `Parallel Seq Scan` (224 ms single-threaded before V6) |
| `LIKE` + trigram index | 278 / 272 | 176 / 180 ms | 239 / 248 ms | 330 / 334 ms | 9.3 ms, `Bitmap Index Scan` on 3 trigram indexes |
| full-text (GIN) | 804 / 789 | 61 / 62 ms | **102 / 103 ms** | 123 / 125 ms | 2.6 ms, `Bitmap Index Scan` on `events_search_idx` |

- Loading 100,000 events: about 1 s. The GIN index: 1.7 MB.
- Goal "search under 500 ms": `LIKE` fails it under load (p95 2.7 s). Both indexes meet it;
  full-text with about 5x room to spare.

## What surprised us

- One `LIKE` search alone took only 74 ms. With 50 people at once, it took 2.7 s. Reading
  100,000 rows is fine once; 50 at a time, the CPUs are the queue.
- An index did not just make each query faster; it raised how many searches per second the
  machine can serve 14x (trigram) to 40x (full-text).
- Full-text and `LIKE` find **different things**. Full-text misses word parts (`jaz`) but
  finds word forms (`concerts`). Pick by what users type. The design's Elasticsearch adds
  typo tolerance ("Tayler" -> "Taylor"), which neither can do.
- A normal B-tree index would not help `'%jazz%'` at all: B-trees can only find text by its
  beginning. That is why both fixes use GIN.

## Quiz (multiple choice)

### Q1. Why is `name ILIKE '%jazz%'` slow on 100,000 events?

- A) Postgres is slow at text.
- B) The leading `%` means "jazz" can be anywhere, so without a special index Postgres reads every row and checks it.
- C) ILIKE is case-insensitive, which is expensive.
- D) The events table has no primary key.

<details><summary>Answer</summary>

**B.** `EXPLAIN` shows `Seq Scan`: every row read. A normal B-tree index cannot help with a
leading `%`. Postgres is fast at text when it has the right index (not A). Case-insensitivity
is a small cost, not the main one (not C). The table has a primary key; it does not help a
text search (not D).
</details>

### Q2. A user searches `jaz`. Full-text finds nothing; `LIKE` finds "Quiet Jazz Night". Why?

- A) Full-text search is broken.
- B) Full-text compares whole words (stems) from the GIN index; `jaz` is not a word in any event. `LIKE` compares letters anywhere.
- C) `jaz` is too short for Postgres.
- D) The GIN index is not up to date.

<details><summary>Answer</summary>

**B.** The index stores words like `jazz`, `night`. It can find `concerts` (stem `concert`)
but not word parts. For "search as you type" you would add prefix matching or the trigram
index. Nothing is broken or stale (not A or D); length is not the issue (not C).
</details>

### Q3. Why GIN for both fixes, not a normal (B-tree) index?

- A) GIN is newer.
- B) A B-tree sorts whole values, so it can only find text by its beginning; GIN maps each word (or 3-letter piece) to the rows that contain it, so it can find words in the middle.
- C) B-tree indexes do not work on text.
- D) GIN uses less disk.

<details><summary>Answer</summary>

**B.** GIN is an inverted index: word -> rows. That is what search needs. B-trees work on
text, but only for equality and prefix (`LIKE 'jazz%'`), not `'%jazz%'` (not C). Age and size
are not the reason (not A or D).
</details>

## What to understand

- Without an index, `LIKE '%word%'` reads every row. Fine alone, too slow under load: p95
  2.7 s with 50 users on 100,000 events.
- A GIN index maps words to rows (an inverted index). Full-text with GIN: p95 102 ms, about
  40x the throughput.
- Full-text and `LIKE` answer different questions: words and their forms vs letters anywhere.
  Pick the one that matches what users type.
