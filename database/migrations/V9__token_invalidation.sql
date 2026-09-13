-- S-7: session invalidation on password change.
-- Access tokens issued before tokens_valid_from are rejected by
-- JwtAuthenticationFilter; refresh tokens are deleted outright. NULL means
-- no invalidation has ever been requested for the account.
BEGIN;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS tokens_valid_from TIMESTAMP WITHOUT TIME ZONE NULL;

COMMIT;
