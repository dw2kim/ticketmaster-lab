# ADR-0006: Waiting room in Redis, inside the app, with polling

- **Status:** accepted
- **Date:** 2026-11-20
- **Step:** 06

## Context

A flash sale sends 10,000 buyers at once at 20 seats (`docs/design/05-deep-dives.md`
section 4). Without help, the booking path timed out for 18% of buyers (step 6 numbers).

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Push seat changes with SSE (design's "good") | Fresher seat map | Does not reduce load on booking; seats still vanish in a second |
| Waiting room, clients **poll** their position | Simple HTTP; server suggests how long to wait | Polling is extra requests (about 80k in 86 s) |
| Waiting room, server **pushes** position (SSE/WebSocket) | No polling load | Long-lived connections; more code |
| Waiting room at the edge (CDN / separate service) | Takes the first rush away from the app | Outside a laptop lab |

## Decision

Build a Redis waiting room inside the app, with polling, off by default
(`queue.enabled=false`). It is one idea and shows the core: fair order, admit N per second,
a gate in the booking path, expiring admissions.

Measured: booking path p95 30 s -> 4-9 ms; 0 errors behind the queue. The ~1,800 errors at
the very first join stayed, which documents why real waiting rooms live at the edge.

## Consequences

- Booking endpoints check admission when the waiting room is on (403 otherwise).
- Future work, if wanted: SSE for position updates; tell the line when the event is sold out;
  move the join step in front of the app.
