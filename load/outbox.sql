-- Step 10: the outbox at a glance.
--   waiting      notes not delivered to Elasticsearch yet
--   oldest_wait  how long the oldest waiting note has waited
--   delivered    notes already delivered
SELECT count(*) FILTER (WHERE sent_at IS NULL)                          AS waiting,
       date_trunc('second', now() - min(created_at) FILTER (WHERE sent_at IS NULL)) AS oldest_wait,
       count(*) FILTER (WHERE sent_at IS NOT NULL)                      AS delivered
  FROM outbox;
