# ADR-0011: Deploy on Render, without Elasticsearch

- **Status:** accepted
- **Date:** 2026-11-27
- **Step:** deploy (after step 10)

## Context

We want the lab on the internet: the app, Postgres and Redis, set up the way a small team
would, but cheap and simple enough for one person learning. Guide: [docs/deploy.md](../deploy.md).

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| One VM (e.g. a $6 droplet) + `docker compose up` | Cheapest; same files as the laptop | We patch the OS, back up Postgres, renew TLS, restart on crash. Not what teams do for a database. |
| AWS (ECS/Fargate + RDS + ElastiCache) | What big companies use; scales far | Many moving parts (VPC, IAM, load balancer); easy to get a surprise bill; weeks to learn |
| **Render (PaaS)** | Docker app + managed Postgres + managed Redis; private network; HTTPS; deploy on push; one `render.yaml` | Less control; ~$23 / month for paid plans; no managed Elasticsearch |
| Fly.io / Railway | Similar PaaS | Similar trade-offs; Render's Blueprint file was the clearest fit |

## Decision

Render, with a Blueprint (`render.yaml`), a `Dockerfile`, and GitHub Actions CI as the gate
before `main`. Elasticsearch is left out: search uses Postgres full-text (the step 8 default,
ADR-0008), and Elastic Cloud would cost more than everything else combined.

Two choices inside it:

- Config from environment variables with laptop defaults, so local runs do not change.
- `/actuator/health` checks Postgres but not Redis. Measured locally: with Redis stopped the
  event page still returns 200 (cache fallback), so failing health would take a working app
  out of service.

## Consequences

- Easier: deploy = merge to `main`; no servers to patch; database backups by Render.
- Harder: the endpoints have no auth, so the public URL is a demo only; load tests must stay
  local; we depend on Render's plans and prices.
- At real scale: an API gateway with auth and rate limits in front, a staging environment,
  and likely AWS or GCP with Elasticsearch (or OpenSearch) managed there.
