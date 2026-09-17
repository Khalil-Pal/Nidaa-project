-- CS-1: consultation records.
-- consultations had no link to assignments, so nothing could say which assignment
-- produced which session; assignment_id (nullable, FK) adds it. The record is
-- addressed by the psychological request (POST/GET .../{id}/consultation, and the
-- feedback under it), so there is exactly one per case: the service refuses a
-- second with 409 and the unique constraint makes the database refuse it as well
-- when two submissions race. Its index replaces the plain index on the same
-- column. Idempotent; safe to rerun by hand.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'consultations' AND column_name = 'assignment_id'
    ) THEN
        ALTER TABLE consultations
            ADD COLUMN assignment_id BIGINT NULL REFERENCES assignments(assignment_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_consultations_psych_request'
    ) THEN
        ALTER TABLE consultations
            ADD CONSTRAINT uq_consultations_psych_request UNIQUE (psychological_request_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_consultations_assignment ON consultations (assignment_id);

DROP INDEX IF EXISTS idx_consultations_psych_request;
