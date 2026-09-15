-- D-2: accounts are anonymised in place instead of deleted, so aid history,
-- assignments and messages survive with the person unidentifiable.
-- UserRepository.softDelete sets deleted_at, is_active = false, a placeholder
-- email and name, and phone = NULL. That requires phone to be nullable and
-- non-unique (the UNIQUE constraint also blocked households sharing a phone;
-- both changes were planned for D-6 and are made here instead).

ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_phone_key;

