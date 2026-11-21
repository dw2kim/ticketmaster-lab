# 00: Approach

## The framework

The Hello Interview approach builds a design in a fixed order:

```
Requirements --> Core entities --> API --> High-level design --> Deep dives
 (what, top 3)    (the nouns)     (contract)  (one FR at a time)    (driven by NFRs)
```

Rules we take from it:

- **Top 3 functional requirements only.** Everything else is written down as *below the line*
  so we know we chose not to build it.
- **Functional requirements first, then non-functional ones.** Get a simple design that works,
  then let the non-functional requirements (consistency, scale, latency) point at what breaks.
- **Start with a simple API and evolve it.** `POST /bookings` becomes *reserve* + *confirm*
  once we need holds.
- **Deep dives compare options.** For each problem, look at a bad, a good and a great option
  and say why. In this lab we go further: we **run** the options and compare numbers.
- **Don't follow rules blindly.** For example, the design deliberately shares one Postgres
  database across services because booking needs ACID transactions across tickets and bookings.

## How the lab steps map onto the framework

| Lab step | Framework stage | Design doc |
| --- | --- | --- |
| 1. The problem, on paper | Requirements, entities, API | 01, 02, 03 |
| 2. The naive app | High-level design: view event and book (simple booking flow) | 04 |
| 3. Break it: 200 buyers, 20 tickets | Shows why the consistency NFR needs work | 05 section 1 |
| 4. Fix it three ways | Deep dive: no double booking (row lock vs OCC vs conditional update) | 05 section 1 |
| 5. Holds: reserve now, pay in 10 min | Deep dive: reserving tickets (status+expiry, Redis lock with TTL) | 05 section 2 |
| 5b. Holds in Redis *(optional)* | Deep dive: reserving tickets (Redis lock with TTL), compared with step 5 | 05 section 2 |
| 6. Flash sale and a waiting room | Deep dive: high-demand events (virtual waiting queue) | 05 section 4 |
| 7. Fast reads | Deep dive: scaling the view API (cache) | 05 section 3 |
| 8. Fast search | Deep dive: low-latency search (indexes, full-text, caching) | 05 section 5, section 6 |

Steps 7-8 are new proposals that complete the design's deep dives; they are listed in the
README as optional.
