-- S-9: a password-reset code may be guessed at most 5 times.
-- attempts counts wrong codes submitted for the current token; the token is
-- deleted on the fifth wrong code so the 15-minute window cannot be brute-forced.

ALTER TABLE password_reset_tokens
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;

