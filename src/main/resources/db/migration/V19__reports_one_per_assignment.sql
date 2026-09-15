-- R-1: exactly one completion report per assignment.
-- The service refuses a second report with 409; this makes the database refuse
-- it as well when two requests race, so the row count can never disagree with
-- the rule. The unique constraint's index replaces the plain index on the same
-- column. Idempotent; safe to rerun by hand.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_reports_assignment'
    ) THEN
        ALTER TABLE reports
            ADD CONSTRAINT uq_reports_assignment UNIQUE (assignment_id);
    END IF;
END $$;

DROP INDEX IF EXISTS idx_reports_assignment;
