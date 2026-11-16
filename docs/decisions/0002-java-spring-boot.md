# ADR-0002: Java 21 + Spring Boot, with plain SQL

- **Status:** accepted
- **Date:** 2026-11-16
- **Step:** 02

## Context

Step 2 needs an app. The lab is about concurrency on a hot row, so the code must make the
SQL and the transaction boundaries visible, and must be able to simulate hundreds to
thousands of concurrent buyers.

## Options

| Choice | Picked | Why / why not the others |
| --- | --- | --- |
| Language | **Java 21 (LTS)** | Your choice. **Virtual threads** make 10,000 concurrent simulated buyers cheap. |
| Framework | **Spring Boot 3** (Web, JDBC, Validation, Actuator) | Your choice. Mainstream, and `@Transactional` makes transaction boundaries explicit. |
| Data access | **`JdbcClient` / `JdbcTemplate` with hand-written SQL** | JPA/Hibernate hides the SQL and adds its own locking and caching, which would hide exactly what we're studying. JPA's `@Version` OCC can still be shown side by side in step 4 if useful. |
| Migrations | **Flyway** | Plain `.sql` files that are easy to read; each step adds its columns with a new migration. |
| Build | **Gradle (Kotlin DSL) with wrapper** | Short build file. Maven would also be fine. |
| Redis client (step 5+) | **Spring Data Redis (Lettuce)** | Supports `SET NX EX`, sorted sets and Lua scripts. |
| Tests | **JUnit 5 + Testcontainers** (Postgres, Redis) | Tests hit a real Postgres, so concurrency results are real, not mocked. |
| Load scripts | **A small Java runner on virtual threads** (`loadtest` module/task) | No extra tool to install; it prints the result table straight into step notes. k6 is an option for the HTTP-level spike in step 6. |
| Infra | **Docker Compose** (Postgres 16, Redis 7) | As planned in ADR-0001. |

## Decision

Java 21 + Spring Boot 3, plain SQL with `JdbcClient`, Flyway, Gradle, Testcontainers, and a
virtual-thread load runner.

## Consequences

- The SQL in each step's code is exactly the SQL in the design docs. That makes them easy to
  compare.
- Step 4's three fixes are three short repository methods, not three frameworks.
- No ORM conveniences; we write the row mapping. That's fine at this size.
