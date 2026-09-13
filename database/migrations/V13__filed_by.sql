-- ON-1: on-behalf-of filing.
-- beneficiary_id always identifies the person who needs help. filed_by_user_id
-- records the volunteer or organization that filed the request for them, and
-- stays NULL for self-filed requests (every row that exists before this
-- migration).
BEGIN;

ALTER TABLE help_requests
    ADD COLUMN IF NOT EXISTS filed_by_user_id BIGINT NULL REFERENCES users(user_id);

CREATE INDEX IF NOT EXISTS idx_help_requests_filed_by
    ON help_requests(filed_by_user_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_help_requests_filer_not_beneficiary'
    ) THEN
        ALTER TABLE help_requests
            ADD CONSTRAINT chk_help_requests_filer_not_beneficiary
            CHECK (filed_by_user_id IS NULL OR filed_by_user_id <> beneficiary_id);
    END IF;
END $$;

COMMIT;
