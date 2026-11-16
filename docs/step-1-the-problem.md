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

## Questions to think about (answers below)

1. If 200 people buy at once and the server works as above, could **more than 20** people be
   told "you got a ticket"? Could two people share one ticket?
2. Java has `synchronized`, which lets only one thread at a time into a piece of code. Would
   wrapping steps 1-3 in `synchronized` fix it? What if the site runs on **two servers**?
3. What would you need so that steps 1 and 3 can never be separated?

<details><summary>Answers</summary>

1. Yes to both. Every buyer who looks during someone else's gap sees a ticket that is about to
   be taken, and is told they got it. Step 3 of this lab measures how many.
2. On one server, yes: only one request at a time can be between "look" and "write". On two
   servers, no: each server has its own lock, so one request on each server can be in the gap
   at the same time. This is why the fix must live in the **database**, the one thing both
   servers share.
3. Make "look and write" one single action the database does all at once (for example "give
   me a free ticket and mark it sold, in one statement"), or lock the ticket so nobody else can
   look at it until you are done. Step 4 tries three ways.
</details>

## What to understand

- The bug is not in any single line; it is in the **gap** between looking and writing.
- One-at-a-time tests cannot see it; only many requests **at the same moment** can.
- A lock inside one server does not help when there are two servers; the fix belongs in the
  shared database.
