-- GAP-1 and GAP-2: an auto-assigned provider can decline, and a request that
-- nobody takes is retried and eventually escalated instead of being forgotten.
--
-- needs_attention marks a request an administrator has to look at: three
-- providers declined it, or it waited past the escalation age with no
-- assignment. needs_attention_at and needs_attention_reason say when and why, so
-- the admin queue can explain itself and the flag can be cleared by assignment.
--
-- The declines themselves are assignment rows with status 'DECLINED'
-- (assignments.status is varchar(20) with no value CHECK, so no type change is
-- needed): they keep who declined and when, which is what the rematch excludes
-- on and what the count of three is taken from.
--
-- The partial index serves the retry sweep, which reads PENDING requests in
-- priority order; the attention index serves the admin queue.
-- Idempotent; safe to rerun by hand.

ALTER TABLE help_requests
    ADD COLUMN IF NOT EXISTS needs_attention BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS needs_attention_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS needs_attention_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_help_requests_pending_priority
    ON help_requests (priority_score DESC, request_id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_help_requests_needs_attention
    ON help_requests (needs_attention_at)
    WHERE needs_attention;

CREATE INDEX IF NOT EXISTS idx_assignments_request_status
    ON assignments (request_id, status);
