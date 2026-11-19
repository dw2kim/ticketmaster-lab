# 01: Requirements

## Functional ("users should be able to...")

**Above the line (we build these):**

1. View an event: details, venue, performer, and a seat map showing which seats are free.
2. Search for events by keyword, date, location, type.
3. Book tickets to an event.

**Below the line (written down, not built):**

- View my booked events.
- Admins create or edit events. (We seed data instead.)
- Dynamic pricing for popular events.

## Non-functional ("the system should...")

**Above the line:**

1. **Availability for reads, consistency for booking.** Viewing and searching should stay up,
   even if slightly stale. Booking must never double-sell a seat. (CAP: AP for reads, CP for
   writes.)
2. **Handle popular events.** Very high traffic on a single event (the design uses ~10M users
   for one event). Our lab scales this down: hundreds, then ~10,000 simulated buyers.
3. **Low-latency search**: under 500 ms.
4. **Read-heavy**: about 100 reads per write.

**Below the line:** GDPR, fault tolerance, payment security (beyond not handling raw card
data), CI/CD, backups.

## What this means for the lab

| Requirement | Shows up in |
| --- | --- |
| No double booking | Steps 3, 4, 5 (the core of the lab) |
| Popular events | Step 6 (spike + waiting room) |
| Read-heavy, available reads | Step 7 (cache) |
| Search < 500 ms | Step 8 (indexes / full-text) |
