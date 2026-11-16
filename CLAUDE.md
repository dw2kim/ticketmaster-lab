# CLAUDE.md

Guidance for Claude (and humans) working in this repo.

## What this repo is

A **learning lab**, not a product. It answers one question: *many people, few items, the same
moment — how do you never sell the same ticket twice?*

The design follows the Hello Interview "Design Ticketmaster" breakdown
(<https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster>). Our own
summary of that design lives in [`docs/design/`](docs/design/) and is the **source of truth**
for what we build. If code and design disagree, fix one of them in the same change and say
which.

## How the design is approached (the framework)

Every piece of work follows the same order. Don't skip ahead.

1. **Requirements** — functional ("users can…") and non-functional ("the system should…"),
   top 3 only, the rest written down as *below the line*. → `docs/design/01-requirements.md`
2. **Core entities** — the nouns. → `docs/design/02-core-entities.md`
3. **API** — the smallest interface that meets the requirements. → `docs/design/03-api.md`
4. **High-level design** — satisfy each functional requirement one by one, simplest thing
   that works. → `docs/design/04-high-level-design.md`
5. **Deep dives** — use the non-functional requirements to find what breaks, then compare
   bad / good / great options with numbers. → `docs/design/05-deep-dives.md`

Lab steps 1–3 cover 1–4; steps 4 onward are deep dives. See the step table in `README.md`
and the mapping in `docs/design/00-approach.md`.

## The invariant

**A ticket is sold at most once.** Every step must keep this true or demonstrably break it on
purpose (step 3). The database (PostgreSQL) is the source of truth for ticket state; anything
in Redis is an optimization or a short-lived hold, never the record of a sale.

## How to work in this repo

- **One idea per step.** A step adds the minimum code to show its idea. No speculative
  features, no frameworks the step does not need.
- **The user is learning.** Explain *why*, show the numbers, keep code short and readable.
  Prefer a small script the user can run and read over a clever abstraction.
- **Measure, don't claim.** Concurrency claims need a load script and a result table
  (e.g. "200 buyers, 20 tickets → 20 sold, 180 rejected, p95 = …").
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
| Redis (ticket lock with TTL, event cache) | Redis in Docker (from step 5) |
| Event / Search / Booking services | Packages in **one** Spring Boot app (separate services only if a step needs it) |
| Stripe + webhook | Fake payment endpoint that calls our confirm webhook |
| Virtual waiting queue | Redis-backed queue (step 6) |
| Elasticsearch + CDC | Postgres full-text search first; Elasticsearch only as a stretch step |
| API gateway / load balancer / CDN | Out of scope; mentioned in notes only |

**Application** ([ADR-0002](docs/decisions/0002-java-spring-boot.md)):

- **Java 21 + Spring Boot 3** (Web, JDBC, Validation, Actuator), built with **Gradle (Kotlin DSL)**
  and its wrapper.
- **Plain SQL through `JdbcClient`/`JdbcTemplate`, no JPA.** The SQL is the lesson, so keep
  it visible. Keep transactions short and explicit with `@Transactional`; never hold one open
  across a user's think time.
- **Flyway** migrations in `src/main/resources/db/migration`; each step adds its own
  `V<n>__<what>.sql` and never edits an old one.
- **Spring Data Redis (Lettuce)** from step 5.
- **Tests:** JUnit 5 + Testcontainers (real Postgres/Redis). Concurrency tests assert the
  invariant (sold ≤ tickets), not timings.
- **Load runner:** a small Java program on virtual threads that prints a markdown result
  table to paste into the step note.

Layout (one module per service idea, all in one app): `event`, `search`, `booking`,
`payment` (fake), `queue` (step 6), under `src/main/java/.../ticketmaster/`.

## Commands

Planned. They become real when step 2 scaffolds the app; fix them here if they change.

```bash
docker compose up -d          # Postgres (+ Redis from step 5)
./gradlew bootRun             # run the app on :8080
./gradlew test                # unit + Testcontainers tests (needs Docker)
./gradlew loadtest --args="--buyers 200 --tickets 20 --strategy naive"
```
