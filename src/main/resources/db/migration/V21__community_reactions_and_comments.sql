-- CM-1: server-backed likes and comments on community posts.
-- Likes and comments lived in each browser's localStorage, so two browsers showed
-- different counts. message_reactions holds one row per (post, user); its primary
-- key is that pair, which is the UNIQUE the design asks for. message_comments is
-- soft-deleted like posts, and a moderated comment is recorded in message_deletions
-- with comment_id set (message_id is then the comment's post), the same audit row a
-- removed post gets. Idempotent; safe to rerun by hand.

CREATE TABLE IF NOT EXISTS message_reactions (
    message_id BIGINT    NOT NULL REFERENCES messages(message_id),
    user_id    BIGINT    NOT NULL REFERENCES users(user_id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT pk_message_reactions PRIMARY KEY (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_message_reactions_user ON message_reactions(user_id);

CREATE TABLE IF NOT EXISTS message_comments (
    id         BIGSERIAL PRIMARY KEY,
    message_id BIGINT    NOT NULL REFERENCES messages(message_id),
    author_id  BIGINT    NOT NULL REFERENCES users(user_id),
    content    TEXT      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    is_deleted BOOLEAN   NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_message_comments_message ON message_comments(message_id, created_at);

ALTER TABLE message_deletions
    ADD COLUMN IF NOT EXISTS comment_id BIGINT NULL REFERENCES message_comments(id);

COMMENT ON COLUMN message_deletions.comment_id IS
    'Set when the moderated item is a comment; message_id is then the comment''s post. NULL for a removed post.';
