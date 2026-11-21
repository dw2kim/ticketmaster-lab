# ADR-0009: Elasticsearch as an optional search strategy; bulk reindex only

- **Status:** accepted
- **Date:** 2026-11-21
- **Step:** 09

## Context

Step 8 made search fast with Postgres full-text (ADR-0008). The design's "great" option is a
dedicated search engine with CDC. We wanted to see what it adds and what it costs, keeping
the lab small.

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Stay on Postgres full-text | One system; always fresh; ~700 req/s, p95 115 ms | No typo tolerance; date order only |
| Elasticsearch, **bulk reindex** (built) | Typos, relevance ranking; copy rebuilt in 5.6 s | Stale between reindexes; +1 GB memory; not faster here |
| + outbox table and a sync worker | Fresh within seconds; reliable | A worker, retries, more code |
| + CDC (Debezium/Kafka) | Fresh, no app code changes | 2-3 more containers; mostly operations work |
| Elasticsearch Java client instead of plain HTTP | Typed API | Hides the JSON we want to read |

## Decision

Add `search.strategy=elastic` with a bulk reindex at startup (`search.reindex-on-start`).
Keep `fulltext` as the **default**: our events never change while the app runs, and
Elasticsearch was not faster here (542-615 vs 685-716 req/s). When Elasticsearch is down,
`elastic` falls back to Postgres full-text.

## Consequences

- Elasticsearch is in `compose.yaml` and needs about 1 GB of memory.
- If events start changing while the app runs (admin endpoints), add the outbox or CDC.
- Zero-downtime reindex (build a new index, switch an alias) is not done; searches during a
  reindex find little.
