# ADR-0012: Also deploy on AWS, defined in Terraform

- **Status:** accepted
- **Date:** 2026-11-27
- **Step:** deploy (after step 10)

## Context

Render (ADR-0011) is the small-team setup. We also want to see the setup bigger companies
run: their cloud's own managed Postgres and Redis, a load balancer, a private network, all
as code. Guide: [docs/deploy-aws.md](../deploy-aws.md).

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| AWS console, by hand | Nothing to learn first | Can't repeat it, can't review it, easy to forget a piece (and keep paying for it) |
| **Terraform** | Most common tool in industry; plain files; `destroy` removes everything; works for other clouds too | One more tool; a state file to keep safe |
| AWS CDK (Java) | Same language as the app | AWS only; more code than the infrastructure it describes |
| CloudFormation (YAML) | AWS native, no extra tool | Verbose; slower to read and to fail |

Inside AWS: ECS Fargate (no servers) over EKS (Kubernetes: too much for one app) or EC2
(servers to patch).

## Decision

Terraform in `infra/aws/`, next to Render (both stay). ECS Fargate on ARM, RDS PostgreSQL 17,
ElastiCache Redis 7 with `noeviction`, an ALB, a VPC with private subnets for the data.
Two cost cuts with their reason in the code: no NAT gateway (the task gets a public IP but
only the ALB may connect), and single-AZ data stores. Elasticsearch stays out, as on Render.

## Consequences

- Easier: the setup is reviewable in a PR, repeatable, and removable with one command.
- Harder: about $70 / month vs Render's ~$23; deploys are manual (`docker push` +
  `update-service`) until CI deploys with OIDC; local Terraform state.
- At real scale: remote state in S3, HTTPS, NAT + private app subnets, Multi-AZ, autoscaling,
  OpenSearch (see "What a real company adds" in the guide).
