BEGIN;

-- Messages share one table, but direct conversations and community posts have
-- different addressing rules. Every insert must choose its type explicitly.
ALTER TABLE messages
    ADD COLUMN message_type VARCHAR(20) NOT NULL,
    ADD COLUMN community_category VARCHAR(50),
    ALTER COLUMN receiver_id DROP NOT NULL;

ALTER TABLE messages
    ADD CONSTRAINT chk_messages_message_type
        CHECK (message_type IN ('DIRECT', 'COMMUNITY')),
    ADD CONSTRAINT chk_messages_receiver_by_type
        CHECK (
            (message_type = 'DIRECT' AND receiver_id IS NOT NULL)
            OR
            (message_type = 'COMMUNITY' AND receiver_id IS NULL)
        ),
    ADD CONSTRAINT chk_messages_category_by_type
        CHECK (message_type = 'COMMUNITY' OR community_category IS NULL);

COMMENT ON COLUMN messages.message_type IS
    'Explicit message kind: DIRECT for private conversations or COMMUNITY for the shared feed.';

COMMENT ON COLUMN messages.receiver_id IS
    'Required for DIRECT messages and required to be NULL for COMMUNITY messages.';

COMMENT ON COLUMN messages.community_category IS
    'Normalized community feed category; must be NULL for DIRECT messages.';

COMMIT;
