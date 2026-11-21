# ADR-0007: Cache event details for 1 hour; seat map cache off by default

- **Status:** accepted
- **Date:** 2026-11-21
- **Step:** 07

## Context

The event page is read far more than anything else (read:write about 100:1,
`docs/design/01-requirements.md`), and everyone refreshes the same page during a sale
(`docs/design/05-deep-dives.md` section 3).

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| No cache | Always fresh | 2 DB calls per request; slowest (p95 about 155 ms) |
| Cache details only (long TTL) | Safe: details do not change; halves DB calls | Small speed gain; the seat map query remains |
| Also cache the seat map (short TTL) | 99% fewer DB calls, +55% throughput, p95 about 72 ms | Seat map up to TTL seconds stale; stampede at each expiry |
| Spring `@Cacheable` instead of hand-written read-through | Less code | Hides the get/miss/set steps we want to see |

## Decision

`cache.event-details-ttl=PT1H` on by default: it is never wrong in this lab.
`cache.seat-map-ttl=PT0S` (off) by default: a stale seat map is a product decision. It
never breaks the invariant (reserve checks the database), but buyers would click seats that
are already gone. Turn it on with `PT1S` when reads are the bottleneck.

## Consequences

- Admin edits to events (out of scope) would need to delete `event:{id}:details`.
- If the seat map cache is turned on at scale, add stampede protection (single refill per key).
