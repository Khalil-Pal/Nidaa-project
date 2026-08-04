BEGIN;

-- profiles.latitude and profiles.longitude are the canonical location for
-- every user role: BENEFICIARY, VOLUNTEER, ORGANIZATION, and PSYCHOLOGIST.
-- volunteers.latitude and volunteers.longitude remain temporarily so the
-- current matching code keeps working until its read path moves to profiles.
CREATE INDEX IF NOT EXISTS idx_profiles_coordinates
    ON profiles(latitude, longitude);

COMMENT ON COLUMN profiles.latitude IS
    'Canonical latitude for location-aware behavior across all user roles.';
COMMENT ON COLUMN profiles.longitude IS
    'Canonical longitude for location-aware behavior across all user roles.';
COMMENT ON COLUMN volunteers.latitude IS
    'Legacy matching latitude; migrate reads to profiles.latitude before removing.';
COMMENT ON COLUMN volunteers.longitude IS
    'Legacy matching longitude; migrate reads to profiles.longitude before removing.';

CREATE TABLE IF NOT EXISTS provider_resources (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id),
    help_type help_type NOT NULL,
    capacity_mode VARCHAR(20) NOT NULL,
    capacity_amount INTEGER,
    capacity_label VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_resources_user_help_type
        UNIQUE (user_id, help_type),
    CONSTRAINT chk_provider_resources_capacity_mode
        CHECK (capacity_mode IN ('NUMERIC', 'QUALITATIVE')),
    CONSTRAINT chk_provider_resources_capacity_value
        CHECK (
            (capacity_mode = 'NUMERIC' AND capacity_amount IS NOT NULL)
            OR
            (capacity_mode = 'QUALITATIVE' AND capacity_label IS NOT NULL)
        )
);

COMMENT ON TABLE provider_resources IS
    'Provider capacity by help type. The application restricts rows to VOLUNTEER and ORGANIZATION users.';

CREATE INDEX IF NOT EXISTS idx_provider_resources_help_type
    ON provider_resources(help_type);

ALTER TABLE volunteers
    ADD COLUMN IF NOT EXISTS occupation VARCHAR(150);

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS message_deletions (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES messages(message_id),
    deleted_by_admin_id BIGINT NOT NULL REFERENCES users(user_id),
    original_author_id BIGINT NOT NULL REFERENCES users(user_id),
    reason TEXT NOT NULL,
    original_content TEXT NOT NULL,
    deleted_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMIT;
