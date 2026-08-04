BEGIN;

ALTER TABLE assignments
    ADD COLUMN IF NOT EXISTS resource_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS resource_help_type help_type,
    ADD COLUMN IF NOT EXISTS reserved_capacity_amount INTEGER,
    ADD COLUMN IF NOT EXISTS capacity_restored_at TIMESTAMP;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_assignments_resource_user'
    ) THEN
        ALTER TABLE assignments
            ADD CONSTRAINT fk_assignments_resource_user
            FOREIGN KEY (resource_user_id)
            REFERENCES users(user_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_assignments_capacity_reservation'
    ) THEN
        ALTER TABLE assignments
            ADD CONSTRAINT chk_assignments_capacity_reservation
            CHECK (
                (
                    resource_user_id IS NULL
                    AND resource_help_type IS NULL
                    AND reserved_capacity_amount IS NULL
                    AND capacity_restored_at IS NULL
                )
                OR
                (
                    request_type = 'HELP_REQUEST'
                    AND resource_user_id IS NOT NULL
                    AND resource_help_type IS NOT NULL
                    AND reserved_capacity_amount > 0
                )
            )
            NOT VALID;
    END IF;
END $$;

ALTER TABLE assignments
    VALIDATE CONSTRAINT fk_assignments_resource_user,
    VALIDATE CONSTRAINT chk_assignments_capacity_reservation;

CREATE INDEX IF NOT EXISTS idx_assignments_active_capacity_reservation
    ON assignments(resource_user_id, resource_help_type, status)
    WHERE reserved_capacity_amount IS NOT NULL
      AND capacity_restored_at IS NULL;

COMMENT ON COLUMN assignments.resource_user_id IS
    'Provider user whose numeric resource capacity was reserved for this assignment.';
COMMENT ON COLUMN assignments.resource_help_type IS
    'Normalized help type of the reserved numeric provider resource.';
COMMENT ON COLUMN assignments.reserved_capacity_amount IS
    'Exact numeric amount deducted when the assignment was created.';
COMMENT ON COLUMN assignments.capacity_restored_at IS
    'Set once when a cancelled assignment returns its reserved capacity.';

COMMIT;
