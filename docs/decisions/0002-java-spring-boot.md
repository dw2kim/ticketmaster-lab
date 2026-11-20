# ADR-0002: Java 25 + Spring Boot 4.1, Maven, plain SQL, k6

- **Status:** accepted
- **Date:** 2026-11-16
- **Step:** 02

## Context

Step 2 needs an app. The lab is about concurrency on a hot row, so the code must keep the
SQL and the transaction boundaries visible, and we must be able to fire hundreds to
thousands of concurrent buyers at it.

## Options

| Choice | Picked | Why / why not the others |
| --- | --- | --- |
| Language | **Java 25 (LTS)** | Current LTS, replacing Java 21. |
| Framework | **Spring Boot 4.1.x**, Spring MVC | Daewon's choice. `@Transactional` makes transaction boundaries explicit. |
| Build | **Maven** (with wrapper) | Daewon's choice (an earlier draft said Gradle). |
| Data access | **`JdbcClient`, hand-written SQL** | JPA/Hibernate hides the SQL and adds its own locking and caching: the very thing we study. |
| Migrations | **Flyway** | Plain `.sql` files; each step adds its columns in a new migration. |
| Redis (step 5+) | **Spring Data Redis** | Supports `SET NX EX`, sorted sets, Lua. |
| Tests | **JUnit 5 + Testcontainers** | Real Postgres, so concurrency results are real. |
| Load tests | **k6** (from step 3), run in Docker | Real HTTP load with a summary of p95 and error counts; nothing to install. (An earlier draft used a Java runner.) |
| Infra | **Docker Compose** (Postgres, Redis) | As planned in ADR-0001. |

## Decision

Java 25 + Spring Boot 4.1.x + Maven, `JdbcClient` with plain SQL, Flyway, JUnit 5 +
Testcontainers, k6, Docker Compose.

## Consequences

- The SQL in each step's code is the SQL in the design docs. That makes them easy to compare.
- Step 4's three fixes are three short repository methods.
- k6 tests through HTTP, so numbers include the web layer, which is closer to real life.
  Testcontainers tests check the invariant without HTTP.
