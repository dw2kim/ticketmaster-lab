# 03: API

Start simple, then evolve. Each version below is tied to the lab step that introduces it.

## View an event (step 2)

```http
GET /events/:eventId  →  Event & Venue & Performer & Ticket[]
```

The tickets come back so the client can draw the seat map.

## Search (step 2 naive, step 8 fast)

```http
GET /events/search?keyword=…&start=…&end=…&location=…&type=…&pageSize=…&page=…  →  Partial<Event>[]
```

## Book: first version (step 2)

```http
POST /bookings/:eventId  →  bookingId
{ "ticketIds": ["…"], "paymentDetails": "…" }
```

One call checks the tickets, takes payment and marks them sold. Its weakness: you can fill in
payment details and only then find out the seat is gone.

## Book: reserve then confirm (step 5)

```http
POST /bookings/reserve   { "eventId": "…", "ticketIds": ["…"] }    →  { bookingId, expiresAt }
POST /bookings/confirm   { "bookingId": "…", "paymentToken": "…" } →  { status }
POST /webhooks/payment   { "bookingId": "…", "result": "succeeded" }   # from the payment provider
```

- *reserve* holds the seats for 10 minutes and creates a booking `in_progress`.
- *confirm* starts the payment; the real result arrives on the webhook.
- The webhook marks tickets `booked` and the booking `confirmed` in one transaction. It must
  be **idempotent**: providers retry, so use `bookingId` as the idempotency key and check the
  current status before changing anything.
- If that transaction fails because the hold expired and someone else booked the seat, the
  payment is refunded automatically (fake refund in the lab). Starting a payment extends the
  hold so this stays rare.
- From step 6, *reserve* is rejected unless the user is in `admitted:{eventId}` (the waiting
  room let them in).

The user id comes from the auth token in a real system; in the lab we pass a header such as
`X-User-Id`.
