# ADR-0003: Default booking strategy is the atomic conditional update

- **Status:** accepted
- **Date:** 2026-11-20
- **Step:** 04

## Context

Step 3 showed the naive look-then-write booking oversells (28 to 42 "yes" for 20 seats).
Step 4 built three fixes (`docs/design/05-deep-dives.md` section 1). Later steps (holds,
waiting room) build on one of them.

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Row lock (`SELECT ... FOR UPDATE`) | Easy to read; losers get a clear answer after waiting | Buyers queue on hot rows; lock order matters for multi-seat bookings (deadlocks) |
| OCC (`version` column) | No waiting; works across longer read-think-write flows | Still has a gap (it detects it); extra column; many failures when contention is high |
| Conditional update (`WHERE status = 'available'`) | No gap at all; lock held for one statement; shortest code | Only fits when the whole check can be written in the `WHERE` clause |

## Decision

Default `booking.strategy=conditional`. All three were correct in every run (20 sold, 0
cheated, step 4 table). Timings at 200 buyers were close and noisy; conditional was a little
faster (p95 87-133 ms vs 114-222 ms). It wins on simplicity, not speed.

The other strategies stay in the code for comparison.

## Consequences

- Step 5 (holds) uses the same pattern: "reserve only if available or the hold expired" fits
  in one `UPDATE ... WHERE`.
- OCC stays relevant: the design uses it as the final guard when a Redis hold is lost.
- At real scale we would re-measure with more seats and longer runs.
