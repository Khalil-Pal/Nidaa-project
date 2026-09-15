-- D-3: "no rating yet" is NULL, not a number.
-- The entities defaulted rating to 0.0 while the CHECK required 1..5, so a
-- provider profile persisted through JPA with the default was rejected. The
-- column default of 5.0 meant every provider approved through the application
-- looked top-rated before anyone had rated them. Nothing has ever written a
-- real rating (that arrives with R-1 / CS-1 / AGG-1), so existing values are
-- defaults and are reset. Safe to re-run.

ALTER TABLE volunteers ALTER COLUMN rating DROP DEFAULT;
ALTER TABLE volunteers DROP CONSTRAINT IF EXISTS rating_range;
ALTER TABLE volunteers ADD CONSTRAINT rating_range
    CHECK (rating IS NULL OR (rating >= 1 AND rating <= 5));

ALTER TABLE psychologists ALTER COLUMN rating DROP DEFAULT;
ALTER TABLE psychologists DROP CONSTRAINT IF EXISTS rating_range;
ALTER TABLE psychologists ADD CONSTRAINT rating_range
    CHECK (rating IS NULL OR (rating >= 1 AND rating <= 5));

-- Every stored value so far is the former column default, not a rating.
UPDATE volunteers    SET rating = NULL WHERE rating = 5.0;
UPDATE psychologists SET rating = NULL WHERE rating = 5.0;

