# Step 1: the problem, on paper

No code yet. Just the one idea this whole lab is about.

## The setup

A concert has **20 tickets**. At 10:00 sharp, **200 people** press "Buy" at the same moment.

Correct outcome: **exactly 20 people** get a ticket, **each ticket goes to one person**, and 180
people are told "sold out".

## How a simple server sells a ticket

```
1. look:   "Is there a free ticket?"      -> yes, ticket #7 is free
2. decide: "OK, give #7 to this buyer"
3. write:  "ticket #7 now belongs to Ana"
```

One buyer at a time, this is perfect.

## Two buyers at the same instant

```
time   Ana's request                    Ben's request
----   ------------------------------   ------------------------------
 1     look: #7 is free
 2                                      look: #7 is free      <- Ana has not written yet
 3     write: #7 belongs to Ana
 4                                      write: #7 belongs to Ben   <- overwrites Ana
       Ana is told "you got #7"         Ben is told "you got #7"
```

Both were told "you got #7". The database says Ben. Ana shows up at the concert with a ticket
that is not hers. Nothing crashed and no error was logged: each request did the right thing
on its own. The problem lives **between step 1 and step 3**: the gap.

This is called a **race condition**, and this pattern is called **check-then-act**.

## Why it is easy to miss

- With one tester clicking, the gap is microseconds and nobody ever lands in it.
- With 200 people in the same second, many land in it.
- So tests that send one request at a time all pass, and the bug only shows up on the day
  that matters.

## Quiz (multiple choice)

Pick one answer per question, then open "Answer" to check.

### Q1. 200 people buy at once and the server works as above. What can happen?

- A) Exactly 20 people get a ticket. The database keeps it safe.
- B) More than 20 people can be told "you got a ticket", and two people can be told they got the same seat.
- C) Fewer than 20 tickets are sold, but never more.
- D) The server crashes with an error.

<details><summary>Answer</summary>

**B.** Every buyer who looks during someone else's gap sees a seat that is about to be
taken, and is told they got it. Nothing crashes (not D), and the database alone does not
stop it (not A). Step 3 measures how many extra "winners" there are.
</details>

### Q2. You wrap steps 1-3 in Java `synchronized`. Does it fix the bug?

- A) Yes, always.
- B) No, never.
- C) Yes on one server, but no once the site runs on two servers.
- D) Only if the database is PostgreSQL.

<details><summary>Answer</summary>

**C.** `synchronized` lets one thread at a time into the code, but only inside one Java
process. With two servers, each has its own lock, so one request on each server can be in
the gap at the same time. The fix must live in the **database**, the one thing both servers
share.
</details>

### Q3. What makes steps 1 and 3 impossible to separate?

- A) Make the server faster, so the gap is smaller.
- B) Retry the request if it fails.
- C) Check twice before writing.
- D) Let the database do "look and write" as one action, or lock the seat until you are done.

<details><summary>Answer</summary>

**D.** For example: "mark seat #7 sold only if it is still free", in one SQL statement. A
faster server (A) makes the gap smaller but never zero. Checking twice (C) just adds a
second gap. Retrying (B) does not stop two people from both "winning". Step 4 tries three
ways to do D.
</details>

## What to understand

- The bug is not in any single line; it is in the **gap** between looking and writing.
- One-at-a-time tests cannot see it; only many requests **at the same moment** can.
- A lock inside one server does not help when there are two servers; the fix belongs in the
  shared database.

## Read next

The full design this lab follows: [requirements](../design/01-requirements.md),
[core entities](../design/02-core-entities.md), [API](../design/03-api.md).
