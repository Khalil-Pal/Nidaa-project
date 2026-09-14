-- L-2: two-tier crisis detection.
-- is_crisis (score >= 3, or an explicit crisis category) is routed to an
-- on-duty psychologist automatically. needs_review marks the 1-2 point band:
-- worth a human look, not an automatic crisis. Existing rows default to false.
BEGIN;

ALTER TABLE psychological_requests
    ADD COLUMN IF NOT EXISTS needs_review BOOLEAN NOT NULL DEFAULT FALSE;

COMMIT;
