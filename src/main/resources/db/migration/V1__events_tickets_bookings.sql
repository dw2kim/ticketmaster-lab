-- Step 2: the smallest schema that lets us view, search and book.
-- Venue and performer are plain columns for now (see docs/steps/step-02-the-naive-app.md).

CREATE TABLE events (
    id             BIGSERIAL PRIMARY KEY,
    name           TEXT        NOT NULL,
    description    TEXT        NOT NULL,
    venue_name     TEXT        NOT NULL,
    performer_name TEXT        NOT NULL,
    starts_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE bookings (
    id         BIGSERIAL PRIMARY KEY,
    user_id    TEXT        NOT NULL,
    status     TEXT        NOT NULL,          -- confirmed (more states come in step 5)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tickets (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT  NOT NULL REFERENCES events (id),
    seat        TEXT    NOT NULL,
    price_cents INT     NOT NULL,
    status      TEXT    NOT NULL DEFAULT 'available',   -- available | booked
    booking_id  BIGINT  REFERENCES bookings (id)
);
