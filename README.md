# Ticketmaster lab

A tiny standalone lab to learn one idea: **many people, few items, at the same moment.**
How do you never sell the same ticket twice?

Part of a learning path; the bigger project is
[childcare-backend-lab](https://github.com/dw2kim/childcare-backend-lab), whose "Seats" phase is
this same problem with childcare rules on top.

## Steps

| # | Step | You do | Status |
| --- | --- | --- | --- |
| 1 | The problem, on paper (no code) | Read, answer 3 questions | Done: `docs/step-1-the-problem.md` |
| 2 | The naive app: one table, one endpoint | Buy tickets one at a time: it works | Next |
| 3 | Break it: 200 buyers, 20 tickets, all at once | Count how many were sold | |
| 4 | Fix it three ways, compare the numbers | Run each fix, read the table | |
| 5 | Holds: reserve now, pay within 10 minutes | Watch a hold expire | |
| 6 | Flash sale: 10,000 buyers and a waiting room | Run the spike | |

Each step: one idea, short notes in `docs/`, and three "what to understand" bullets at the end.
