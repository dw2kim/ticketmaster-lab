# Ticketmaster lab

A small learning lab for Daewon. One idea: never sell the same ticket twice under contention.
Steps and status: `README.md`. Notes per step: `docs/step-N-*.md`.

## How to work here (set by Daewon, 2026-11-16)

- Slow and small: one idea per step. Never start the next step unless asked.
- Short chat messages. No long walkthroughs unless asked.
- Daewon runs things himself; give the exact commands.
- End every step with three bullets: "What to understand".
- Keep the app tiny: one table, few endpoints, no login, no tenants.
- Plain ASCII in docs; short sentences.

## Stack

Java 21, Spring Boot 4.1.x, Maven, Spring MVC, plain JDBC (`JdbcClient`), PostgreSQL, Flyway,
JUnit 5, Testcontainers, k6 (from step 3), Docker Compose.
