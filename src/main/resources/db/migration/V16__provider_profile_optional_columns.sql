-- Gate 3 finding: approving a psychologist or an organization through the
-- application always failed on a fresh schema. AdminController inserts the
-- profile row with only the columns the registration form collects, but
-- psychologists.specialization and organizations.registration_number were
-- NOT NULL without defaults, so the INSERT was rejected and the approval
-- transaction rolled back. Neither value is known at approval time; providers
-- complete their profile afterwards.

ALTER TABLE psychologists ALTER COLUMN specialization DROP NOT NULL;
ALTER TABLE organizations ALTER COLUMN registration_number DROP NOT NULL;

