# ADR-0008: Search with Postgres full-text (GIN); trigram index optional; no Elasticsearch

- **Status:** accepted
- **Date:** 2026-11-21
- **Step:** 08

## Context

The requirements ask for search under 500 ms (`docs/design/01-requirements.md`). With
100,000 events and 50 people searching, the step 2 search (`LIKE '%keyword%'`) had p95 2.7 s
(`docs/steps/step-08-search.md`).

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| `LIKE`, no index (step 2) | Simplest; finds word parts | Reads every row: p95 2.7 s, 20 req/s |
| B-tree indexes on name etc. (design's "good") | Normal indexes | Do not help `'%word%'` at all |
| `LIKE` + `pg_trgm` GIN indexes | Same query; word parts still work | 3 indexes; p95 ~245 ms, ~275 req/s |
| Postgres full-text + GIN (design's "great") | Words, word forms, phrases, `-exclude`; p95 ~102 ms, ~800 req/s; one small index | No word parts (`jaz`), no typo tolerance |
| Elasticsearch + CDC (design's "great") | Typo tolerance, ranking, scales out | A second system to run and keep in sync |

## Decision

Default `search.strategy=fulltext`. It meets the goal with the most room, needs no new
infrastructure, and Postgres keeps the index in sync by itself. `like` stays available, and
`load/trigram-index.sql` shows the other Postgres option. Elasticsearch stays a stretch goal.

## Consequences

- Users who type word parts ("Tay") find nothing. Fixes if needed: prefix queries
  (`to_tsquery('tay:*')`) or the trigram index.
- Repeated searches are not cached (design section 6). Step 7's `@Cacheable` would work here
  too, with the keyword in the cache key.
