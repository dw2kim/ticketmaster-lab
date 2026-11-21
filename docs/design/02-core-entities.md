# 02: Core entities and data model

## Entities

| Entity | What it is |
| --- | --- |
| **Event** | One show: name, description, date, type; points at a venue and a performer. |
| **Venue** | A place: address, capacity, and a **seat map** (sections, rows, seats, coordinates for drawing). |
| **Performer** | Who is on stage: artist, team, company. |
| **Ticket** | One seat at one event: section/row/seat, price, status. Created for every seat when the event is created. |
| **Booking** | One purchase: user, the tickets in it, total price, status. Groups several tickets under one payment. |
| **User** | The person buying. In the lab, just an id. |

Why a separate Booking and not just a column on Ticket: one purchase often covers several
seats with a single payment status and total.

## Data model (sketch, PostgreSQL)

```text
events      (id, name, description, type, starts_at, venue_id, performer_id)
venues      (id, name, location, seat_map jsonb)
performers  (id, name, description)
tickets     (id, event_id, section, row, seat, price_cents,
             status,            -- available | reserved | booked
             reserved_until,    -- set during a hold (step 5)
             booking_id,        -- null until reserved/booked
             version)           -- for optimistic concurrency (step 4)
bookings    (id, user_id, status,  -- in_progress | confirmed | expired | cancelled
             seat_count,        -- step 5: confirm sells all seats or none
             ticket_ids,        -- step 5b: with Redis holds, the booking remembers its seats
             total_cents, created_at, confirmed_at)
```

Columns marked with a step are added in that step, not up front.

**Lab simplification (step 2):** `venues` and `performers` are not separate tables yet;
`events` has `venue_name` and `performer_name` text columns. Split them out only if a step
needs it. Prices and totals: tickets have `price_cents`; `bookings.total_cents` comes later.

`reserved` and `reserved_until` belong to the **implicit-expiry** hold. In the **Redis-lock**
variant of step 5, tickets have only `available | booked`, and holds live in Redis
(`ticket:{id}` -> userId, plus a sorted set `event:{id}:held` for the seat map). See
`05-deep-dives.md` section 2.

The seat map lives on the venue; the client draws it and colors each seat from that seat's
ticket status.
