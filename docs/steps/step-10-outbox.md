# Step 10: keep the search copy fresh with an outbox

**Design link:** `docs/design/05-deep-dives.md` section 5 (keeping Elasticsearch in sync)
**Idea in one sentence:** write the change and a "to-do note" in the same transaction, and let
a worker deliver the note to Elasticsearch, so no change is ever lost.

## What we built

Events can now change: `PUT /events/{id}` `{"name":"..."}` renames one. (No admin endpoints is
the lab's rule; this one exists only to create changes to sync.) How a change reaches
Elasticsearch is chosen with `search.sync`:

| `search.sync` | How | If Elasticsearch is down |
| --- | --- | --- |
| `none` (default) | not at all; only the next reindex (step 9) | nothing changes |
| `dual-write` | commit Postgres, **then** call Elasticsearch | the change is **lost**: Postgres has it, Elasticsearch never will |
| `outbox` | change + note in **one** transaction; `OutboxWorker` delivers the note | the note **waits**, and is delivered when Elasticsearch is back |

The outbox (`V7__outbox.sql`, `EventUpdates`, `OutboxWorker`):

```
one transaction:                                    worker, every 500 ms:
  UPDATE events SET name = ... WHERE id = 5           SELECT ... FROM outbox WHERE sent_at IS NULL
  INSERT INTO outbox (event_id) VALUES (5)              ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
  -- both commit, or neither                         send events 5, ... to Elasticsearch (_bulk)
                                                     UPDATE outbox SET sent_at = now() ...
```

- **No lost changes:** the note commits with the change. A failed change leaves no note either.
- **At least once:** if the worker dies after sending but before marking, the note is sent
  again. Harmless, because each send writes the whole event under its id (**idempotent**).
- **`FOR UPDATE SKIP LOCKED`:** two workers (two app instances) never deliver the same note
  at the same time; each skips notes the other holds.
- **The worker sends the event's current state**, not "what changed". Two renames of the same
  event in one batch become one send.

Two more fixes came with it:

- `_bulk` answers HTTP 200 even when documents fail. `ElasticIndexer` now reads the `errors`
  flag and throws, so a failed send is retried instead of marked as sent.
- A rename evicts the step 7 cache (`event-details`), or the event page would show the old
  name for up to an hour.

New: `V7__outbox.sql`, `event/EventUpdates`, `event/EventUpdateController`,
`search/OutboxWorker`, `OutboxTest`, `SyncWhenElasticDownTest`, `ElasticTestContainer`
(one Elasticsearch for all tests), `load/sync.js`, `load/outbox.sql`.

## How to run it

Two terminal windows, both in the `ticketmaster-lab` folder. **Terminal 1 runs the app**
(stop it with `Ctrl+C`). **Terminal 2 is for everything else.** Elasticsearch needs about
1 GB of memory (see step 9).

### 0. Once, before you start

**Terminal 1:**

```bash
cd ~/ticketmaster-lab
```

```bash
git fetch origin && git checkout step-10-outbox && git pull
```

```bash
docker compose up -d
```

**Terminal 2:**

```bash
cd ~/ticketmaster-lab
```

Wait until Elasticsearch answers (20 to 60 s):

```bash
curl -s localhost:9200
```

Start from the 2 seed events, with their original names, and an empty outbox:

```bash
docker compose exec postgres psql -U lab -d ticketmaster -c "DELETE FROM events WHERE id > 2; UPDATE events SET name = 'Lab Concert' WHERE id = 1; UPDATE events SET name = 'Quiet Jazz Night' WHERE id = 2;"
```

### Part A: a change, delivered by the outbox

**Terminal 1.** Start with Elasticsearch search, outbox sync, and a fresh copy. Wait for
`reindexed 2 events into Elasticsearch`:

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--search.strategy=elastic --search.sync=outbox --search.reindex-on-start=true"
```

**Terminal 2.**

1. Rename event 1. You should see `{"name":"Zebra Parade"}`:

```bash
curl -s -X PUT localhost:8080/events/1 -H 'Content-Type: application/json' -d '{"name":"Zebra Parade"}'
```

2. Look at the outbox. You should see `waiting 0`, `delivered 1`: the worker already sent it
   (it runs every 500 ms):

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/outbox.sql
```

3. Ask Elasticsearch directly. You should see `"Zebra Parade"`:

```bash
curl -s localhost:9200/events/_search -H 'Content-Type: application/json' -d '{"query":{"match":{"name":"zebra"}}}'
```

### Part B: Elasticsearch down, the outbox waits

Keep the app running.

1. **Terminal 2.** Stop Elasticsearch:

```bash
docker compose stop elasticsearch
```

2. Rename event 2. It still works (`{"name":"Walrus Gala"}`): the change only needs Postgres:

```bash
curl -s -X PUT localhost:8080/events/2 -H 'Content-Type: application/json' -d '{"name":"Walrus Gala"}'
```

3. Look at the outbox. You should see `waiting 1` and how long it has waited:

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/outbox.sql
```

In **Terminal 1**: `Elasticsearch unavailable: outbox notes wait and will be retried`.

4. Start Elasticsearch, wait until `curl -s localhost:9200` answers, then look again. You
   should see `waiting 0`, `delivered 2`, and in Terminal 1
   `Elasticsearch is back: delivering waiting outbox notes`:

```bash
docker compose start elasticsearch
```

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/outbox.sql
```

5. Elasticsearch has the new name. You should see `"Walrus Gala"`:

```bash
curl -s localhost:9200/events/_search -H 'Content-Type: application/json' -d '{"query":{"match":{"name":"walrus"}}}'
```

### Part C: the same with dual-write (the broken way)

**Terminal 1.** `Ctrl+C`, then:

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--search.strategy=elastic --search.sync=dual-write"
```

**Terminal 2.**

1. Stop Elasticsearch, then rename event 1:

```bash
docker compose stop elasticsearch
```

```bash
curl -s -X PUT localhost:8080/events/1 -H 'Content-Type: application/json' -d '{"name":"Penguin Party"}'
```

The caller sees success. In **Terminal 1**:
`dual-write: Elasticsearch update LOST for event 1`.

2. Start Elasticsearch, wait until it answers, then search for the new name. You should see
   `"total":{"value":0`: the change never arrived, and nothing will retry it:

```bash
docker compose start elasticsearch
```

```bash
curl -s localhost:9200/events/_search -H 'Content-Type: application/json' -d '{"query":{"match":{"name":"penguin"}}}'
```

3. Elasticsearch still has the **old** name (`"Zebra Parade"`), while Postgres says
   `Penguin Party`:

```bash
curl -s localhost:9200/events/_search -H 'Content-Type: application/json' -d '{"query":{"match":{"name":"zebra"}}}'
```

```bash
docker compose exec postgres psql -U lab -d ticketmaster -c "SELECT id, name FROM events WHERE id = 1;"
```

Only a full reindex fixes it (step 9's `--search.reindex-on-start=true`).

### Part D: measure lag and lost changes

Load the 100,000 events (step 8), then for each run: start the app in Terminal 1 (wait for
`reindexed 100002 events`), run k6 in Terminal 2, `Ctrl+C` in Terminal 1.

```bash
docker compose exec -T postgres psql -U lab -d ticketmaster < load/seed-events.sql
```

| Run | Terminal 1 |
| --- | --- |
| outbox | `./mvnw spring-boot:run "-Dspring-boot.run.arguments=--search.strategy=elastic --search.sync=outbox --search.reindex-on-start=true"` |
| dual-write | `./mvnw spring-boot:run "-Dspring-boot.run.arguments=--search.strategy=elastic --search.sync=dual-write --search.reindex-on-start=true"` |

**Terminal 2** (5 renames per second for 60 s; each one is checked until it shows up in
Elasticsearch, or for 90 s):

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e ES_URL=http://host.docker.internal:9200 grafana/k6 run - < load/sync.js
```

Look at `sync_lag_ms` and `changes_lost`. For the outage version, in a **third** terminal,
about 15 s after k6 starts: `docker compose stop elasticsearch`, wait 20 s,
`docker compose start elasticsearch`.

### When you are done

```bash
docker compose down -v
```

### If something goes wrong

| You see | Why | Fix |
| --- | --- | --- |
| `relation "outbox" does not exist` | The app has not run migration V7 yet | Start the app once (Part A), then retry |
| Outbox shows `waiting` that never drops | App not started with `--search.sync=outbox`, or Elasticsearch down | Check the Terminal 1 command; `curl -s localhost:9200` |
| Elasticsearch search finds nothing at all | No reindex yet | Start with `--search.reindex-on-start=true` |
| `404` on `PUT /events/...` | No event with that id | Use 1 or 2 (or 3 to 100002 after loading Part D) |
| `curl: (7)` on port 9200 right after `start` | Elasticsearch still starting | Wait 20 to 60 s |

## Numbers

100,002 events, `load/sync.js`: 5 renames per second for 60 s, each checked in Elasticsearch
every 200 ms for up to 90 s. Outage: Elasticsearch stopped from about 15 s to 35 s.

| Run | Renames made | Synced | **Lost** | Lag p50 | Lag p95 | Lag max |
| --- | --- | --- | --- | --- | --- | --- |
| outbox | 300 | 300 | **0** | 815 ms | 1.41 s | 1.62 s |
| dual-write | 301 | 301 | **0** | 609 ms | 1.01 s | 1.21 s |
| outbox, 20 s outage | 253 | 253 | **0** | 8.9 s | 37.0 s | 39.0 s |
| dual-write, 20 s outage | 251 | 108 | **143** | 615 ms | 1.03 s | 39.5 s |

- Outbox during the outage: **106 notes waiting**, the oldest 22 s old; all delivered after.
- Dual-write: 143 `update LOST` lines in the app log, exactly the 143 lost changes.
- In the outage runs k6 skipped about 50 renames (not enough checkers ready). Those renames
  were never made, so they are not counted as lost. The script now starts more checkers.
- Lag includes Elasticsearch's own refresh (about every 1 s), so about 1 s is the floor.

## What surprised us

- With everything healthy, **dual-write looks better**: about 0.2 s less lag, no worker, no
  table. The difference only shows when something fails, and then it is total: 143 of 251
  changes never reached search, silently.
- With the outbox, an outage turns into **lag, not loss**: changes waited up to 39 s and then
  all arrived.
- The bug that would have hurt most was quiet: Elasticsearch's `_bulk` answers HTTP 200 even
  when documents fail. Without reading `errors`, the worker would mark notes as sent that
  never arrived.
- Renaming an event also has to clear the step 7 cache. Every copy of the data (cache,
  search index) needs a plan for changes.

## Quiz (multiple choice)

### Q1. Why must the outbox note be written in the SAME transaction as the change?

- A) It is faster.
- B) So both commit or neither does: no change without a note, and no note for a change that rolled back.
- C) Postgres does not allow two transactions.
- D) The worker can only read notes from its own transaction.

<details><summary>Answer</summary>

**B.** That is the whole trick. Two separate writes (dual-write) can half-succeed; one
transaction cannot. Speed is not the reason (not A). Postgres runs many transactions (not C).
The worker reads notes after they commit, in its own transaction (not D).
</details>

### Q2. The worker sends event 5 to Elasticsearch, then the app crashes before `sent_at` is set. What happens?

- A) The change is lost.
- B) The note is still unsent, so it is sent again later; that is harmless because the whole event is written under its id.
- C) Event 5 appears twice in search.
- D) Postgres rolls back the rename.

<details><summary>Answer</summary>

**B.** At-least-once delivery plus an idempotent send. The rename committed long before
(not D). Writing a document with the same id replaces it, so no duplicate (not C), and
nothing is lost (not A).
</details>

### Q3. With everything healthy, dual-write had less lag than the outbox. Why use the outbox anyway?

- A) The outbox is always faster.
- B) Dual-write lost 143 of 251 changes during a 20 s outage, silently; the outbox lost none, it only delayed them.
- C) Dual-write does not work with Elasticsearch.
- D) The outbox needs no database.

<details><summary>Answer</summary>

**B.** Correctness when things fail beats a fraction of a second when they do not. The
outbox is a little slower (not A). Dual-write works until it does not (not C). The outbox is a
database table (not D).
</details>

## What to understand

- **Dual-write is broken by design:** two separate writes can half-succeed, and nothing
  remembers. In our outage, 143 of 251 changes never reached search.
- **Transactional outbox:** change + note in one transaction; a worker delivers notes at least
  once; sends are idempotent. An outage becomes lag (up to 39 s here), not loss.
- Every copy of the data (cache, search index) needs a plan for changes: evict it, or deliver
  the change to it reliably.
