# Step 9: search with Elasticsearch (small version)

**Design link:** `docs/design/05-deep-dives.md` section 5 ("Great: a dedicated search engine")
**Idea in one sentence:** keep a **copy** of the events in a search engine that forgives typos,
rebuild the copy from Postgres, and see what it costs.

## What we built

A third search strategy, `search.strategy=elastic`, next to `like` and `fulltext` (step 8).

| | Postgres full-text (step 8, default) | Elasticsearch (step 9) |
| --- | --- | --- |
| Where | inside Postgres (GIN index) | a separate server, port 9200 |
| Finds | whole words and their forms | the same, **plus small typos**: `Sparows` -> Sparrows |
| Order | by date | **best match first** (relevance score) |
| Up to date | always: the index is part of the table | only after a **reindex** (it is a copy) |
| If it is down | search is down | search falls back to Postgres full-text |

How it works:

- **The copy:** `ElasticIndexer` reads all events from Postgres in batches of 5,000 and sends
  them to Elasticsearch's `_bulk` API. It runs when the app starts with
  `--search.reindex-on-start=true`. Each event becomes a JSON document:

```json
{"id":5,"name":"Golden Racers: Neon Nights Festival","description":"Golden Racers brings neon nights to Boston. A jazz festival for everyone.","performerName":"Golden Racers","venueName":"Boston Arena","startsAt":"2027-01-04T06:00:00Z"}
```

- **The search** (`EventSearch.elastic`) is plain HTTP + JSON, so you can read it:

```json
POST /events/_search
{ "query": { "multi_match": {
    "query": "Sparows",
    "fields": ["name^2", "performerName^2", "description"],
    "fuzziness": "AUTO" } },
  "size": 20 }
```

  `^2` counts name and performer double. `fuzziness: AUTO` allows 1 wrong letter in words of
  3 to 5 letters, 2 in longer words.
- **The mapping** (`src/main/resources/elasticsearch/events-index.json`) uses the `english`
  analyzer: "Golden Sparrows: Neon Nights Concert" -> `golden sparrow neon night concert`,
  the same words Postgres's `to_tsvector` makes.
- **Postgres stays the source of truth.** Elasticsearch can be thrown away and rebuilt any time.

New: Elasticsearch 9.1.5 in `compose.yaml` (512 MB heap, no security: lab only),
`search/ElasticClient`, `search/ElasticIndexer`, `ElasticSearchTest` (4 tests, real
Elasticsearch), `ElasticDownTest`.

## How to run it

Two terminal windows, both in the `ticketmaster-lab` folder. **Terminal 1 runs the app**
(stop it with `Ctrl+C`). **Terminal 2 is for everything else.**

Elasticsearch needs about **1 GB of memory**. If Docker Desktop is set lower, raise it in
Docker Desktop: Settings, Resources.

### 0. Once, before you start

**Terminal 1:**

```bash
cd ~/ticketmaster-lab
```

```bash
git fetch origin && git checkout step-09-elasticsearch && git pull
```

```bash
docker compose up -d
```

**Terminal 2:**

```bash
cd ~/ticketmaster-lab
```

Wait until Elasticsearch answers (it takes 20 to 60 seconds to start). You should see
`"number" : "9.1.5"` and `"tagline" : "You Know, for Search"`:

```bash
curl -s localhost:9200
```

Add the 100,000 events from step 8 (skip if they are already there). You should see `100002`:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/seed-events.sql
```

### Part A: build the copy, forgive typos

**Terminal 1.** Start the app with Elasticsearch search and a reindex at startup:

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--search.strategy=elastic --search.reindex-on-start=true"
```

Wait for this line (after `Started TicketmasterApplication`):

```
reindexed 100002 events into Elasticsearch in 5606 ms
```

Searches before this line find little or nothing: the copy is still being built.

**Terminal 2.**

1. How many documents does the copy have? You should see `100002`:

```bash
curl -s "localhost:9200/_cat/count/events?v"
```

2. Look at one document:

```bash
curl -s localhost:9200/events/_doc/5
```

3. A typo. You should see 20 results, starting with "... Sparrows ...":

```bash
curl -s "localhost:8080/events/search?keyword=Sparows"
```

4. Another typo. You should see "... Moonrise ..." events:

```bash
curl -s "localhost:8080/events/search?keyword=Moonrse"
```

5. Best match first. You should see `Golden Sparrows: ...` events at the top:

```bash
curl -s "localhost:8080/events/search?keyword=golden%20sparrows"
```

6. Ask Elasticsearch directly, the same query the app sends:

```bash
curl -s localhost:9200/events/_search -H 'Content-Type: application/json' -d '{"query":{"multi_match":{"query":"Sparows","fields":["name^2","performerName^2","description"],"fuzziness":"AUTO"}},"size":2}'
```

Each hit has a `_score`: higher means a better match.

### Part B: the copy goes stale

Keep the app running.

1. **Terminal 2.** Add a new event in Postgres:

```bash
docker compose exec postgres psql -U lab -d ticketmaster -c "INSERT INTO events (name, description, venue_name, performer_name, starts_at) VALUES ('Brand New Show', 'Just announced.', 'Big Hall', 'Newcomers', '2027-03-01 20:00+00');"
```

2. Search for it. You should see `[]`: the copy does not know it yet:

```bash
curl -s "localhost:8080/events/search?keyword=newcomers"
```

3. **Terminal 1.** `Ctrl+C`, then start the app again with the Part A command (it reindexes).
   Wait for `reindexed 100003 events`.

4. **Terminal 2.** Repeat step 2. Now you see `Brand New Show`.

### Part C: Elasticsearch goes down

1. **Terminal 2.** Stop Elasticsearch:

```bash
docker compose stop elasticsearch
```

2. Search. It still works (from Postgres full-text), but `Sparows` now finds nothing,
   because Postgres does not forgive typos:

```bash
curl -s "localhost:8080/events/search?keyword=jazz"
```

```bash
curl -s "localhost:8080/events/search?keyword=Sparows"
```

In **Terminal 1** you see: `Elasticsearch unavailable, using Postgres full-text: ...`

3. Start it again. Its data survives a restart (unlike our memory-only Redis):

```bash
docker compose start elasticsearch
```

### Part D: speed

Same load as step 8 (50 people, 30 s). For each run, start the app in Terminal 1, then run k6
in Terminal 2, then `Ctrl+C` in Terminal 1.

| Run | Terminal 1 |
| --- | --- |
| Elasticsearch | `./mvnw spring-boot:run -Dspring-boot.run.arguments=--search.strategy=elastic` |
| Postgres full-text | `./mvnw spring-boot:run` |

**Terminal 2:**

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/search.js
```

Memory used by each container:

```bash
docker stats --no-stream
```

### When you are done

```bash
docker compose down -v
```

### If something goes wrong

| You see | Why | Fix |
| --- | --- | --- |
| `curl: (52) Empty reply` or `(7) Failed to connect` on port 9200 | Elasticsearch still starting | Wait 30 s, try again |
| Elasticsearch container keeps restarting / `exit 137` | Not enough memory for Docker | Docker Desktop: Settings, Resources: give it more memory |
| Every search returns `[]` | No reindex yet, or still running | Start with `--search.reindex-on-start=true`, wait for `reindexed ...` |
| `Elasticsearch unavailable` in the log | Elasticsearch stopped | `docker compose start elasticsearch` |
| `reindexed 2 events` | The 100,000 events are not loaded | Run `load/seed-events.sql` (step 0) |

## Numbers

Lab machine (4 CPUs; k6, Postgres, Redis, Elasticsearch on the same machine), 100,002 events.

| | Postgres full-text | Elasticsearch |
| --- | --- | --- |
| Requests/s (50 users, 30 s, 2 runs) | 716 / 685 | 542 / 615 |
| p50 | 68 / 71 ms | 90 / 79 ms |
| p95 | 115 / 115 ms | 121 / 105 ms |
| p99 | 143 / 145 ms | 142 / 124 ms |
| Memory | Postgres container: 134 MB in total | Elasticsearch container: 1.07 GB (on top of Postgres) |
| `Sparows` (typo) | 0 results | 20 results, Sparrows first |
| `jaz` | 0 results | Quiet Jazz Night first |
| New event | found at once | missing until reindex |
| Building the copy | not needed | 5.6 s for 100,002 events |

0 failed requests in all runs.

## What surprised us

- **Not faster here.** Every search is an extra HTTP call from the app to Elasticsearch, and
  typo-tolerant matching does more work. At 100,000 events on one laptop, Postgres full-text
  is as fast or faster. Elasticsearch's wins are features (typos, ranking) and scale across
  many servers, not speed on one machine.
- **Memory:** Elasticsearch alone uses about 8x the memory of Postgres.
- **The hard part is the copy, not the search.** A new event is invisible until the next
  reindex. Real systems keep it fresh with an outbox table or CDC (Debezium reading
  Postgres's change log). That is the "medium" and "full" versions, not built here.
- Searches during a reindex find nothing: we delete and rebuild the index in place. Real
  systems build a new index next to the old one and switch an alias when it is ready.

## Quiz (multiple choice)

### Q1. You add an event in Postgres. Search with `search.strategy=elastic` does not find it. Why?

- A) Elasticsearch is broken.
- B) Elasticsearch holds a copy made at the last reindex; the new event is not in the copy yet.
- C) New events need a minute to be indexed in Postgres.
- D) The event has no tickets.

<details><summary>Answer</summary>

**B.** The copy only changes when we reindex. Postgres full-text finds it at once because its
index is part of the table (not C). Nothing is broken (not A); tickets do not matter for
search (not D). Keeping the copy fresh is the real cost of a search engine.
</details>

### Q2. Elasticsearch is down. What does a search do in this lab?

- A) Returns 500.
- B) Returns an empty list.
- C) Falls back to Postgres full-text: same words, no typo tolerance.
- D) Waits until Elasticsearch is back.

<details><summary>Answer</summary>

**C.** Elasticsearch is only a copy; Postgres has everything. So `EventSearch.elastic` catches
the error, logs a warning, and asks Postgres. Search gets worse (no typos), not broken. The
timeouts are short (1 s to connect) so it does not wait long (not D).
</details>

### Q3. Why does `Sparows` find "Sparrows" in Elasticsearch but not in Postgres full-text?

- A) Elasticsearch has more data.
- B) `fuzziness: AUTO` lets a word match with 1 or 2 letters different; Postgres full-text only matches exact words (after stemming).
- C) Postgres does not support English.
- D) The GIN index is missing.

<details><summary>Answer</summary>

**B.** Both have the same 100,002 events (not A). Both use English stemming (not C). The GIN
index is there (not D); it just looks up exact words. Fuzzy matching is a search engine
feature.
</details>

## What to understand

- A search engine keeps a **copy** of your data. Postgres is still the truth; the copy can be
  rebuilt, and goes stale between rebuilds.
- What you buy: typo tolerance and relevance ranking. What you pay: a second system (~1 GB
  here), keeping the copy in sync, and a fallback plan for when it is down.
- At lab scale it was not faster than Postgres full-text. Choose a search engine for its
  features and scale, not because "search engines are fast".
