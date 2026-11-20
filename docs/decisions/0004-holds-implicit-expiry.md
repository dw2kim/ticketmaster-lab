# ADR-0004: Holds use implicit expiry in Postgres first

- **Status:** accepted
- **Date:** 2026-11-20
- **Step:** 05

## Context

Buyers need a seat held while they pay (`docs/design/05-deep-dives.md` section 2). The
design lists four options: long DB locks (bad), status + cron (good), implicit expiry
(great), Redis lock with TTL (great, the design's pick).

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Long DB lock during checkout | Simple to explain | Holds a connection and locks for minutes; no clean timeout |
| Status + expiry + cron release | Easy | Seats stuck until the next cron run; cron failure makes seats look taken |
| Implicit expiry (`reserved_until < now()` counts as free) | No job needed; one SQL rule; reuses step 4's conditional update; no new infra | Raw table can look stale; every reader must apply the rule |
| Redis `SET NX EX` lock | Auto-expiry built in; fast; keeps holds off the DB | New infra; seat map needs a sorted set; DB still needs a final guard |

## Decision

Step 5 builds implicit expiry in Postgres. It is one idea, needs no new infrastructure, and
directly extends step 4's conditional update. Measured: 200 buyers -> exactly 20 holds in 3
of 3 runs; a 30 second hold expires with no job.

The Redis lock becomes an optional later step (5b), so the two greats can be compared.
Update: built in step 5b; the default stays Postgres (ADR-0005).

## Consequences

- Readers (seat map, reserve, confirm) must all use the same "expired = free" rule.
- An optional sweep job can tidy rows later; correctness does not depend on it.
- Payment is faked inline in confirm. A real provider would call a webhook; confirm is
  already idempotent so that change is small.
