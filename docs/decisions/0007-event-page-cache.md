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
| Hand-written read-through (version 1) | Every step visible | More code; we must handle errors, TTLs, stampede ourselves |
| Spring `@Cacheable` + Redis `CacheManager` (version 2) | Standard; less code; locking writer available | Hides the steps; TTL 0 = forever; self-invocation skips the cache; Redis errors fail requests unless handled |
| Locking cache writer (stampede lock) | 1 DB load per expiry (66 vs ~1,500 in 30 s) | Every read checks the lock: ~3,700 vs ~5,600 req/s, p99 300-540 ms |

## Decision

`cache.event-details-ttl=PT1H` on by default: it is never wrong in this lab.
`cache.seat-map-ttl=PT0S` (off) by default: a stale seat map is a product decision. It
never breaks the invariant (reserve checks the database), but buyers would click seats that
are already gone. Turn it on with `PT1S` when reads are the bottleneck.

Update (step 7, version 2): the cache is now Spring's `@Cacheable` with a Redis
`CacheManager` (one TTL per cache, `CacheErrorHandler` falls back to the database, cached methods
in their own bean). Same speed as the hand-written version, which stays as comments.
`cache.lock-on-miss=false` by default: the stampede lock protects the database but slows every
read, and our database is not the bottleneck.

## Consequences

- Admin edits to events (out of scope) would need `@CacheEvict` on `event-details` for that id.
- If the seat map cache is turned on at scale and the database struggles, set `cache.lock-on-miss=true`.
