-- Step 4: a version number per ticket, for optimistic concurrency control (OCC).
-- Every successful write bumps it; a write that expected an old version changes 0 rows.
ALTER TABLE tickets ADD COLUMN version INT NOT NULL DEFAULT 0;
