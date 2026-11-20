# ADR-0005: Keep Postgres as the default hold store; Redis holds stay as an option

- **Status:** accepted
- **Date:** 2026-11-20
- **Step:** 05b

## Context

Step 5 built holds in Postgres (implicit expiry, ADR-0004). Step 5b built the design's pick:
a Redis lock with TTL. Step 6 (waiting room) builds on one of them.

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Postgres holds (default today) | One store; seat map reads one table; no new infra | Every hold is a DB write; raw table can look stale |
| Redis holds | TTL built in; holds off the DB; the design's pick | Second store; seat map needs a sorted set; Redis down = no reserving (503) |

## Decision

Keep `booking.hold-store=postgres` as the default. Both were correct in every run (20 holds
of 200, 0 double sales, lost-lock test passes). Speed was the same within noise at 200
buyers (p95 150-315 ms vs 173-262 ms). At lab scale, one store is simpler.

Redis holds stay available with `--booking.hold-store=redis`.

## Consequences

- Step 6 needs Redis anyway (the waiting queue), so Redis is now part of `compose.yaml`.
- If a later spike shows the DB is the bottleneck for holds, switch the default and re-measure.
