BEGIN;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'volunteers'
          AND column_name IN ('latitude', 'longitude')
    ) AND EXISTS (
        SELECT 1
        FROM volunteers
        WHERE latitude IS NOT NULL OR longitude IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'Cannot remove legacy volunteer coordinates while non-null values remain; migrate them to profiles first.';
    END IF;
END
$$;

ALTER TABLE volunteers
    DROP COLUMN IF EXISTS latitude,
    DROP COLUMN IF EXISTS longitude;

COMMIT;
