-- D-5: one priority model.
-- The V1 triggers recomputed help_requests.priority_score with weights that
-- differ from the documented model in PriorityScoreService (CRITICAL 50 vs 40,
-- children 15 vs 10, people LEAST(n,10) vs 2 each capped at 20, ...). They fired
-- on every insert and on every Hibernate UPDATE (which lists every column), so
-- the Java model was never the persisted score. The application is now the
-- single source of truth and clamps the result to the 0..100 CHECK itself.
BEGIN;

DROP TRIGGER IF EXISTS calculate_priority_before_insert ON help_requests;
DROP TRIGGER IF EXISTS calculate_priority_before_update ON help_requests;
DROP FUNCTION IF EXISTS calculate_priority_score();

COMMIT;
