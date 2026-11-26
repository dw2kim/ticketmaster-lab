-- Step 10: the transactional outbox.
-- Every change to an event also writes a row here, IN THE SAME TRANSACTION. Both commit or
-- neither does, so a change can never happen without its note. The OutboxWorker later sends
-- the event to Elasticsearch and fills in sent_at.
CREATE TABLE outbox (
    id         BIGSERIAL PRIMARY KEY,
    event_id   BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ            -- NULL = not delivered yet
);

-- The worker only ever looks for unsent notes, oldest first.
CREATE INDEX outbox_unsent ON outbox (id) WHERE sent_at IS NULL;
