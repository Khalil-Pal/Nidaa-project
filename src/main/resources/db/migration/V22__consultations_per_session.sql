-- CS-1, owner decision after Gate 5: one consultation record per session, not
-- per case. A psychological case is a course of support; its sessions are the
-- consultations rows, each with its own started_at, ended_at and duration, and
-- the case stays ASSIGNED until the psychologist completes it. V20's
-- UNIQUE (psychological_request_id) contradicted those per-session columns and
-- is dropped; the plain index it replaced comes back, because the sessions of
-- a case are listed by that column. Idempotent; safe to rerun by hand.

ALTER TABLE consultations DROP CONSTRAINT IF EXISTS uq_consultations_psych_request;

CREATE INDEX IF NOT EXISTS idx_consultations_psych_request ON consultations (psychological_request_id);
