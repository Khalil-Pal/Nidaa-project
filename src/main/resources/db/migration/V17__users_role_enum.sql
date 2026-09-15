-- D-6: users.role was character varying while its default was cast to the
-- user_role enum, which existed and was unused by the column. The mismatch is
-- what every "native SQL - avoids Hibernate typing role" workaround existed for.
-- Safe to re-run: casting an enum column to its own type is a no-op.

ALTER TABLE users ALTER COLUMN role TYPE user_role USING role::user_role;

