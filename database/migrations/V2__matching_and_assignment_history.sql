BEGIN;

ALTER TABLE assignments
    ALTER COLUMN request_id DROP NOT NULL;

ALTER TABLE assignments
    ADD COLUMN IF NOT EXISTS psychological_request_id BIGINT,
    ADD COLUMN IF NOT EXISTS psychologist_id BIGINT,
    ADD COLUMN IF NOT EXISTS request_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS assignment_source VARCHAR(32);

UPDATE assignments
SET psychological_request_id = request_id,
    psychologist_id = volunteer_id,
    request_id = NULL,
    volunteer_id = NULL,
    request_type = 'PSYCHOLOGICAL_REQUEST',
    assignment_source = COALESCE(assignment_source, 'MANUAL')
WHERE notes LIKE 'PSYCHOLOGICAL_REQUEST%'
  AND psychological_request_id IS NULL;

UPDATE assignments
SET request_type = CASE
        WHEN psychological_request_id IS NOT NULL THEN 'PSYCHOLOGICAL_REQUEST'
        ELSE 'HELP_REQUEST'
    END,
    assignment_source = COALESCE(assignment_source, 'MANUAL')
WHERE request_type IS NULL
   OR assignment_source IS NULL;

ALTER TABLE assignments
    ALTER COLUMN request_type SET DEFAULT 'HELP_REQUEST',
    ALTER COLUMN request_type SET NOT NULL,
    ALTER COLUMN assignment_source SET DEFAULT 'MANUAL',
    ALTER COLUMN assignment_source SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_assignments_psychological_request'
    ) THEN
        ALTER TABLE assignments
            ADD CONSTRAINT fk_assignments_psychological_request
            FOREIGN KEY (psychological_request_id)
            REFERENCES psychological_requests(request_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_assignments_psychologist'
    ) THEN
        ALTER TABLE assignments
            ADD CONSTRAINT fk_assignments_psychologist
            FOREIGN KEY (psychologist_id)
            REFERENCES psychologists(psychologist_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_assignments_request_reference'
    ) THEN
        ALTER TABLE assignments
            ADD CONSTRAINT chk_assignments_request_reference
            CHECK (
                (request_type = 'HELP_REQUEST'
                    AND request_id IS NOT NULL
                    AND psychological_request_id IS NULL)
                OR
                (request_type = 'PSYCHOLOGICAL_REQUEST'
                    AND request_id IS NULL
                    AND psychological_request_id IS NOT NULL)
            )
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_assignments_source'
    ) THEN
        ALTER TABLE assignments
            ADD CONSTRAINT chk_assignments_source
            CHECK (assignment_source IN ('MANUAL', 'AUTO_GEO', 'AUTO_CRISIS', 'ADMIN'))
            NOT VALID;
    END IF;
END $$;

ALTER TABLE assignments
    VALIDATE CONSTRAINT fk_assignments_psychological_request,
    VALIDATE CONSTRAINT fk_assignments_psychologist,
    VALIDATE CONSTRAINT chk_assignments_request_reference,
    VALIDATE CONSTRAINT chk_assignments_source;

CREATE INDEX IF NOT EXISTS idx_assignments_help_request
    ON assignments(request_id, assigned_at);

CREATE INDEX IF NOT EXISTS idx_assignments_psychological_request
    ON assignments(psychological_request_id, assigned_at);

CREATE INDEX IF NOT EXISTS idx_assignments_psychologist
    ON assignments(psychologist_id, status);

COMMIT;
