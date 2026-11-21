# Ticketmaster lab

A tiny standalone lab to learn one idea: **many people, few items, at the same moment.**
How do you never sell the same ticket twice?

Part of a learning path; the bigger project is
[childcare-backend-lab](https://github.com/dw2kim/childcare-backend-lab), whose "Seats" phase is
this same problem with childcare rules on top.

## Steps

| # | Step | You do | Status |
| --- | --- | --- | --- |
| 1 | The problem, on paper (no code) | Read, answer 3 questions | Done: [`docs/steps/step-01-the-problem.md`](docs/steps/step-01-the-problem.md) |
| 2 | The naive app: view, search, book (no protection) | Buy tickets one at a time: it works | Done: [`docs/steps/step-02-the-naive-app.md`](docs/steps/step-02-the-naive-app.md) |
| 3 | Break it: 200 buyers, 20 tickets, all at once | Count how many were sold | Done: [`docs/steps/step-03-break-it.md`](docs/steps/step-03-break-it.md) |
| 4 | Fix it three ways, compare the numbers | Run each fix, read the table | Done: [`docs/steps/step-04-fix-it-three-ways.md`](docs/steps/step-04-fix-it-three-ways.md) |
| 5 | Holds: reserve now, pay within 10 minutes | Watch a hold expire | Done: [`docs/steps/step-05-holds.md`](docs/steps/step-05-holds.md) |
| 5b | *(optional)* Holds in Redis (`SET NX EX`), compare with step 5 | Kill Redis mid-sale | Done: [`docs/steps/step-05b-redis-holds.md`](docs/steps/step-05b-redis-holds.md) |
| 6 | Flash sale: 10,000 buyers and a waiting room | Run the spike | Done: [`docs/steps/step-06-waiting-room.md`](docs/steps/step-06-waiting-room.md) |
| 7 | *(optional)* Fast reads: cache the event page | Compare with and without cache | Done: [`docs/steps/step-07-event-cache.md`](docs/steps/step-07-event-cache.md) |
| 8 | *(optional)* Fast search: indexes and full-text | Beat 500 ms | Done: [`docs/steps/step-08-search.md`](docs/steps/step-08-search.md) |
| 9 | *(stretch)* Search with Elasticsearch: typos, a copy that goes stale | Find `Sparows` | Done: [`docs/steps/step-09-elasticsearch.md`](docs/steps/step-09-elasticsearch.md) |
| 10 | *(stretch)* Keep the search copy fresh: dual-write vs outbox | Stop Elasticsearch mid-change | Done: [`docs/steps/step-10-outbox.md`](docs/steps/step-10-outbox.md) |

Each step: one idea, short notes in `docs/steps/`, and three "what to understand" bullets at the end.

## Design

The lab implements the [Hello Interview Ticketmaster design](https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster),
built in order: requirements -> entities -> API -> high-level design -> deep dives.
Start at [`docs/README.md`](docs/README.md); the step-to-design mapping is in
[`docs/design/00-approach.md`](docs/design/00-approach.md).
