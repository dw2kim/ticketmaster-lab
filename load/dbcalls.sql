-- Step 7: how many database transactions has Postgres run so far?
-- Each JdbcClient query outside a @Transactional method is one transaction.
-- Run it before and after a load test; the difference / requests = DB calls per request.
SELECT xact_commit AS db_transactions FROM pg_stat_database WHERE datname = 'ticketmaster';
