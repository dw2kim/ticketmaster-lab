# Step 01: The problem, on paper

**Design link:** `docs/design/01-requirements.md`, `02-core-entities.md`, `03-api.md`
**Idea in one sentence:** before writing code, agree on *what* we build, the nouns, and the
contract.

## Read

1. [Requirements](../design/01-requirements.md)
2. [Core entities](../design/02-core-entities.md)
3. [API](../design/03-api.md)

## Answer these three questions (write your answers below each one)

1. **Why is booking CP but viewing AP?** What would go wrong if we made booking "eventually
   consistent", and what would go wrong if we made the event page strictly consistent during a
   spike?

   > …

2. **Where exactly is the race?** Using the `tickets` table, write the two SQL statements the
   naive booking runs, then draw two buyers interleaving them so both "win".

   > …

3. **Why reserve + confirm instead of one `POST /bookings`?** What user problem does the hold
   solve, and what new problem does it create (hint: abandoned checkouts)?

   > …

## What to understand

1. The top 3 requirements decide the whole design; *below the line* is a decision, not
   something we forgot.
2. Only one table is contended: `tickets`. Everything else is read-mostly.
3. The API changes as the design does; starting simple is on purpose.
