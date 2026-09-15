-- Invariant query set (Master Plan, Appendix). Run at every gate against the
-- development database and the fresh gate database. Every count must be 0.
-- A non-zero count is a bug in the application, not data to clean up.
--
--   psql -U postgres -d Web_DB -f scripts/gate/invariants.sql
SELECT 'priority_out_of_range' AS check_name, count(*) AS violations FROM help_requests
  WHERE priority_score < 0 OR priority_score > 100
UNION ALL SELECT 'volunteer_rating_range', count(*) FROM volunteers
  WHERE rating IS NOT NULL AND (rating < 1 OR rating > 5)
UNION ALL SELECT 'psychologist_rating_range', count(*) FROM psychologists
  WHERE rating IS NOT NULL AND (rating < 1 OR rating > 5)
UNION ALL SELECT 'assignment_request_xor', count(*) FROM assignments
  WHERE (request_id IS NULL) = (psychological_request_id IS NULL)
UNION ALL SELECT 'filer_is_beneficiary', count(*) FROM help_requests
  WHERE filed_by_user_id IS NOT NULL AND filed_by_user_id = beneficiary_id
UNION ALL SELECT 'requests_without_beneficiary', count(*) FROM help_requests hr
  LEFT JOIN users u ON u.user_id = hr.beneficiary_id WHERE u.user_id IS NULL
UNION ALL SELECT 'assignments_without_request', count(*) FROM assignments a
  WHERE a.request_type = 'HELP_REQUEST' AND NOT EXISTS
    (SELECT 1 FROM help_requests h WHERE h.request_id = a.request_id)
UNION ALL SELECT 'assigned_without_provider', count(*) FROM help_requests
  WHERE status = 'ASSIGNED'
    AND assigned_volunteer_id IS NULL AND assigned_organization_id IS NULL
UNION ALL SELECT 'completed_without_timestamp', count(*) FROM help_requests
  WHERE status = 'COMPLETED' AND completed_at IS NULL
UNION ALL SELECT 'capacity_negative', count(*) FROM provider_resources
  WHERE capacity_mode = 'NUMERIC' AND capacity_amount < 0
-- Only CANCELLED restores capacity; COMPLETED keeps it consumed (docs/adr/005).
-- The plan's appendix listed both statuses; amended after Gate 4.
UNION ALL SELECT 'unrestored_capacity_on_cancelled', count(*) FROM assignments
  WHERE status = 'CANCELLED'
    AND reserved_capacity_amount IS NOT NULL AND capacity_restored_at IS NULL
UNION ALL SELECT 'restored_capacity_on_completed', count(*) FROM assignments
  WHERE status = 'COMPLETED' AND capacity_restored_at IS NOT NULL
UNION ALL SELECT 'active_deleted_users', count(*) FROM users
  WHERE deleted_at IS NOT NULL AND is_active = true
UNION ALL SELECT 'unanonymised_deleted_users', count(*) FROM users
  WHERE deleted_at IS NOT NULL AND email NOT LIKE 'deleted-%'
UNION ALL SELECT 'expired_tokens_not_swept', count(*) FROM refresh_tokens
  WHERE expires_at < now() - interval '1 day';
