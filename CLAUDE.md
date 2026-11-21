# CLAUDE.md

Guidance for Claude (and humans) working in this repo.

## What this repo is

A **learning lab**, not a product. It answers one question: *many people, few items, the same
moment - how do you never sell the same ticket twice?*

The design follows the Hello Interview "Design Ticketmaster" breakdown
(<https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster>). Our own
summary of that design lives in [`docs/design/`](docs/design/) and is the **source of truth**
for what we build. If code and design disagree, fix one of them in the same change and say
which.

## How the design is approached (the framework)

Every piece of work follows the same order. Don't skip ahead.

1. **Requirements** - functional ("users can...") and non-functional ("the system should..."),
   top 3 only, the rest written down as *below the line*. -> `docs/design/01-requirements.md`
2. **Core entities** - the nouns. -> `docs/design/02-core-entities.md`
3. **API** - the smallest interface that meets the requirements. -> `docs/design/03-api.md`
4. **High-level design** - satisfy each functional requirement one by one, simplest thing
   that works. -> `docs/design/04-high-level-design.md`
5. **Deep dives** - use the non-functional requirements to find what breaks, then compare
   bad / good / great options with numbers. -> `docs/design/05-deep-dives.md`

Lab steps 1-3 cover 1-4; steps 4 onward are deep dives. See the step table in `README.md`
and the mapping in `docs/design/00-approach.md`.

## The invariant

**A ticket is sold at most once.** Every step must keep this true or demonstrably break it on
purpose (step 3). The database (PostgreSQL) is the source of truth for ticket state; anything
in Redis is an optimization or a short-lived hold, never the record of a sale.

## How Daewon wants to work (set 2026-11-16)

- Slow and small: one idea per step. **Never start the next step unless asked.**
- Short chat messages. No long walkthroughs unless asked.
- Daewon runs things himself; give the exact commands. In step notes, "How to run it" says
  for every command which terminal (Terminal 1 = app, Terminal 2 = the rest), which folder
  and branch, and what output to expect. Run the steps yourself first so the expected
  output is real.
- End every step with three bullets: "What to understand".
- Step questions are **multiple choice** (A-D, one correct answer), never free-text. Each one
  has a folded `<details>` "Answer" that names the correct letter and says why the others are
  wrong.
- Keep the app tiny: few tables, few endpoints, no login, no tenants.
- Plain ASCII in docs; short sentences.
- **Branch names say what the change is:** `step-NN-<slug>` for a step (e.g. `step-05-holds`),
  `chore/<what>` or `fix/<what>` otherwise. Never a random name.

## How to work in this repo

- **One idea per step.** A step adds the minimum code to show its idea. No speculative
  features, no frameworks the step does not need.
- **The user is learning.** Explain *why*, show the numbers, keep code short and readable.
  Prefer a small script the user can run and read over a clever abstraction.
- **Measure, don't claim.** Concurrency claims need a load script and a result table
  (e.g. "200 buyers, 20 tickets -> 20 sold, 180 rejected, p95 = ...").
- **Every step ends with notes** in `docs/steps/step-NN-<slug>.md` using
  `docs/steps/_template.md`: what we did, the numbers, and three "what to understand" bullets.
  Update the status column in `README.md`.
- **Decisions get a short ADR** in `docs/decisions/` (template: `docs/decisions/_template.md`)
  when we pick between real alternatives (e.g. pessimistic lock vs OCC vs Redis lock).
- **Keep vocabulary consistent** with the design docs: Event, Venue, Performer, Ticket,
  Booking, User; ticket status `available | reserved | booked`; booking status
  `in_progress | confirmed | expired | cancelled`.

## Scope

In scope (above the line): view an event, search events, book tickets; consistency for
booking, availability for viewing/search; surviving a popular-event spike.

Out of scope unless a step says otherwise: user's booking history, admin event creation
(we seed data instead), dynamic pricing, real payments (a fake payment step stands in for
Stripe), GDPR, backups, CI/CD, multi-region.

## Tech stack

Mirrors the design, scaled down to run on a laptop with Docker Compose:

| Design component | In this lab |
| --- | --- |
| PostgreSQL (events, tickets, bookings) | PostgreSQL in Docker |
| Redis (ticket lock with TTL, event cache) | Redis 7 in Docker, memory only (from step 5b); event page cache with Spring `@Cacheable` + Redis `CacheManager` (step 7, ADR-0007) |
| Event / Search / Booking services | Packages in **one** Spring Boot app (separate services only if a step needs it) |
| Stripe + webhook | Fake payment endpoint that calls our confirm webhook |
| Virtual waiting queue | Redis sorted set + per-user admitted keys, inside the app, polling (step 6, ADR-0006) |
| Elasticsearch + CDC | Postgres full-text search with a GIN index (step 8, ADR-0008, default); Elasticsearch 9 with bulk reindex, no CDC (step 9, ADR-0009) |
| API gateway / load balancer / CDN | Out of scope; mentioned in notes only |

**Application** ([ADR-0002](docs/decisions/0002-java-spring-boot.md)):

- **Java 25 (LTS) + Spring Boot 4.1.x** (Spring MVC), built with **Maven** and its wrapper.
- **Plain SQL through `JdbcClient`, no JPA.** The SQL is the lesson, so keep it visible.
  Keep transactions short and explicit with `@Transactional`; never hold one open across a
  user's think time.
- **Flyway** migrations in `src/main/resources/db/migration`; each step adds its own
  `V<n>__<what>.sql` and never edits an old one.
- **Spring Data Redis** from step 5b. Holds live in Postgres or Redis: `booking.hold-store`
  (`postgres` default, ADR-0005). Redis only holds; Postgres always records the sale.
- **Tests:** JUnit 5 + Testcontainers (real Postgres/Redis). Concurrency tests assert the
  invariant (sold <= tickets), not timings.
- **Load tests:** k6 scripts in `load/` from step 3, run via Docker so nothing extra is
  installed.

Layout (one package per service idea, all in one app): `event`, `search`, `booking`,
`payment` (fake), `queue` (step 6).

## Commands

Working since step 2; load test since step 3:

```bash
docker compose up -d          # Postgres 17 :5432 (lab/lab/ticketmaster), Redis 7 :6379, Elasticsearch 9 :9200
./mvnw spring-boot:run        # run the app on :8080 (booking.strategy=conditional)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--booking.strategy=row-lock   # naive|row-lock|occ|conditional
./mvnw spring-boot:run -Dspring-boot.run.arguments=--booking.hold-duration=PT30S   # short holds (step 5)
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--booking.hold-store=redis --booking.hold-duration=PT30S"   # Redis holds (step 5b)
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--queue.enabled=true --queue.admit-per-second=100"         # waiting room (step 6)
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--queue.enabled=true --queue.admit-per-second=1 --queue.tick=30s"   # waiting room, slow enough to watch
./mvnw spring-boot:run -Dspring-boot.run.arguments=--cache.seat-map-ttl=PT1S   # also cache the seat map (step 7)
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--cache.seat-map-ttl=PT1S --cache.lock-on-miss=true"   # + stampede lock
./mvnw spring-boot:run -Dspring-boot.run.arguments=--search.strategy=like   # LIKE search instead of full-text (step 8)
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--search.strategy=elastic --search.reindex-on-start=true"   # Elasticsearch search (step 9)
./mvnw test                   # tests; start their own Postgres via Testcontainers
docker compose down -v        # stop and wipe the database
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/buy.js   # Mac
docker run --rm -i --network host grafana/k6 run - < load/buy.js                                   # Linux
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/reserve.js   # step 5 rush, Mac
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e QUEUE=1 grafana/k6 run - < load/spike.js   # step 6 spike (QUEUE=0 without)
docker compose exec -T postgres psql -U lab -d ticketmaster < load/count.sql   # promises vs seats
docker compose exec -T postgres psql -U lab -d ticketmaster < load/reset.sql   # all seats back on sale
docker compose exec -T postgres psql -U lab -d ticketmaster < load/holds.sql   # held / expired / sold / free (step 5)
docker compose exec redis redis-cli FLUSHALL                                    # forget all Redis holds and caches
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/view.js   # step 7: 200 viewers, 30 s
docker compose exec -T postgres psql -U lab -d ticketmaster < load/dbcalls.sql   # DB transactions so far (step 7)
docker compose exec -T postgres psql -U lab -d ticketmaster < load/seed-events.sql   # +100,000 events (step 8)
docker compose exec -T postgres psql -U lab -d ticketmaster < load/explain-search.sql   # how each search runs (step 8)
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/search.js   # step 8: 50 searchers, 30 s
curl -s "localhost:9200/_cat/count/events?v"                                     # documents in the Elasticsearch copy (step 9)
```

Tests start their own Postgres and Redis (and Elasticsearch for `ElasticSearchTest`). Code lives in `src/main/java/lab/ticketmaster/{event,search,booking,queue}`; migrations in
`src/main/resources/db/migration`.
