# ADR-0001: Follow the Hello Interview Ticketmaster design, scaled down

- **Status:** accepted
- **Date:** 2026-11-16
- **Step:** 01

## Context

The lab needs one reference design so each step has a clear target, and so the code can be
compared to a well-known interview answer.

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Make it up as we go | Flexible | No target; easy to wander |
| Follow the Hello Interview breakdown | Clear framework (reqs -> entities -> API -> HLD -> deep dives); known bad/good/great options to compare | Built for a whiteboard, not a laptop; some parts (CDN, Elasticsearch, multi-service) are heavy |

## Decision

Follow the Hello Interview design and its framework. Implement it as **one app process + Postgres
(+ Redis from step 5)**, faking payments and leaving gateway/CDN/Elasticsearch as notes or
stretch goals.

## Consequences

- Design docs in `docs/design/` are the source of truth; code changes that diverge must update them.
- "Services" are modules, not deployments: we learn the concurrency ideas without the ops cost.
- Numbers come from local load scripts, so they show *relative* differences between options, not
  production capacity.
