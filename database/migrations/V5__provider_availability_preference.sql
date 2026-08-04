BEGIN;

-- is_available is the provider's effective claim state. The separate
-- availability_preference survives an active assignment so release can restore
-- the provider's chosen state instead of always forcing availability to true.
ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS is_available BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS availability_preference BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE volunteers
    ADD COLUMN IF NOT EXISTS availability_preference BOOLEAN NOT NULL DEFAULT true;

CREATE INDEX IF NOT EXISTS idx_volunteers_matching_availability
    ON volunteers(is_available, availability_preference);

CREATE INDEX IF NOT EXISTS idx_organizations_matching_availability
    ON organizations(is_available, availability_preference);

COMMENT ON COLUMN volunteers.is_available IS
    'Effective matching state; false while claimed or manually unavailable.';
COMMENT ON COLUMN volunteers.availability_preference IS
    'Provider-selected state restored when an active assignment releases its claim.';
COMMENT ON COLUMN organizations.is_available IS
    'Effective matching state; false while claimed or manually unavailable.';
COMMENT ON COLUMN organizations.availability_preference IS
    'Provider-selected state restored when an active assignment releases its claim.';

COMMIT;
