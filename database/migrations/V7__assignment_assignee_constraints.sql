BEGIN;

ALTER TABLE assignments
    ALTER COLUMN volunteer_id DROP NOT NULL,
    ALTER COLUMN assigned_by DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_assignments_assignee_by_type'
    ) THEN
        ALTER TABLE assignments
            ADD CONSTRAINT chk_assignments_assignee_by_type
            CHECK (
                (
                    request_type = 'HELP_REQUEST'
                    AND psychologist_id IS NULL
                    AND (
                        (volunteer_id IS NOT NULL AND organization_id IS NULL)
                        OR
                        (volunteer_id IS NULL AND organization_id IS NOT NULL)
                    )
                )
                OR
                (
                    request_type = 'PSYCHOLOGICAL_REQUEST'
                    AND volunteer_id IS NULL
                    AND organization_id IS NULL
                    AND psychologist_id IS NOT NULL
                )
            )
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_assignments_assigner_by_source'
    ) THEN
        ALTER TABLE assignments
            ADD CONSTRAINT chk_assignments_assigner_by_source
            CHECK (
                (
                    assignment_source IN ('AUTO_GEO', 'AUTO_CRISIS')
                    AND assigned_by IS NULL
                )
                OR
                (
                    assignment_source IN ('MANUAL', 'ADMIN')
                    AND assigned_by IS NOT NULL
                )
            )
            NOT VALID;
    END IF;
END $$;

ALTER TABLE assignments
    VALIDATE CONSTRAINT chk_assignments_assignee_by_type,
    VALIDATE CONSTRAINT chk_assignments_assigner_by_source;

COMMENT ON COLUMN assignments.volunteer_id IS
    'Volunteer assignee for HELP_REQUEST rows; NULL for organization and psychological assignments.';
COMMENT ON COLUMN assignments.assigned_by IS
    'Human assigner for MANUAL or ADMIN rows; NULL for automatic assignment sources.';

COMMIT;
