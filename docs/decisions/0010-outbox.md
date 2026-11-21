# ADR-0010: Keep the Elasticsearch copy fresh with a transactional outbox

- **Status:** accepted
- **Date:** 2026-11-21
- **Step:** 10

## Context

Step 9's Elasticsearch copy only changes at a reindex (ADR-0009). Once events can change
(`PUT /events/{id}`), the copy must follow, without ever disagreeing with Postgres for good.

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Reindex only (`none`) | Simplest | Stale until the next reindex |
| Dual-write | No table, no worker; lag p50 ~0.6 s | Loses changes when Elasticsearch fails: 143 of 251 in a 20 s outage |
| Transactional outbox + polling worker | No loss (0 of 253 in the same outage); at least once; idempotent | A table and a worker; lag p50 ~0.8 s, up to the outage length when down |
| CDC (Debezium reading the WAL) | No app code; also catches changes made outside the app | Debezium (+ usually Kafka): 2-3 more containers |

## Decision

Use the outbox (`search.sync=outbox`) when Elasticsearch search is used. Keep `dual-write`
in the code only to show the failure. Default `search.sync=none`, because the default search
is still Postgres full-text (ADR-0008), which needs no sync. CDC stays in the notes.

## Consequences

- Changes made outside the app (for example `psql`) write no note and are not synced. CDC
  would catch them.
- The outbox table grows; a real system deletes delivered notes after a while.
- The worker holds its transaction during the HTTP call to Elasticsearch (up to 5 s). It
  locks only outbox rows.
