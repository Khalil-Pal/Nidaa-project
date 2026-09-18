package com.humanitarian.platform.repository;

import com.humanitarian.platform.dto.KeyCount;
import com.humanitarian.platform.model.HelpRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HelpRequestRepository extends JpaRepository<HelpRequest, Long> {

    List<HelpRequest> findByBeneficiaryId(Long beneficiaryId);
    Page<HelpRequest> findByBeneficiaryId(Long beneficiaryId, Pageable pageable);

    /** Requests a user asked for, plus those they filed on someone else's behalf (ON-1). */
    @Query("SELECT r FROM HelpRequest r WHERE r.beneficiaryId = :userId OR r.filedByUserId = :userId")
    Page<HelpRequest> findMine(@Param("userId") Long userId, Pageable pageable);
    List<HelpRequest> findByStatus(String status);
    Page<HelpRequest> findByStatus(String status, Pageable pageable);
    List<HelpRequest> findByHelpType(String helpType);
    List<HelpRequest> findByUrgencyLevel(String urgencyLevel);
    List<HelpRequest> findByAssignedVolunteerId(Long volunteerId);
    List<HelpRequest> findByAssignedOrganizationId(Long organizationId);
    long countByStatus(String status);

    // -- statistics: aggregate in the database, never load the table (Q-2) --
    long countByCreatedAtAfter(LocalDateTime since);
    long countByStatusAndCompletedAtAfter(String status, LocalDateTime since);

    @Query("SELECT new com.humanitarian.platform.dto.KeyCount(r.status, COUNT(r)) FROM HelpRequest r GROUP BY r.status")
    List<KeyCount> countGroupedByStatus();

    @Query("SELECT new com.humanitarian.platform.dto.KeyCount(r.helpType, COUNT(r)) FROM HelpRequest r GROUP BY r.helpType")
    List<KeyCount> countGroupedByHelpType();

    @Query("SELECT new com.humanitarian.platform.dto.KeyCount(TRIM(r.address), COUNT(r)) FROM HelpRequest r "
         + "WHERE r.address IS NOT NULL AND TRIM(r.address) <> '' GROUP BY TRIM(r.address)")
    List<KeyCount> countGroupedByAddress();

    // Native SQL — passes status as a bound parameter so stringtype=unspecified
    // handles the PostgreSQL enum cast at the driver level.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET assigned_volunteer_id = :workerId, status = :newStatus WHERE request_id = :id AND status = :currentStatus",
            nativeQuery = true)
    int assignVolunteer(@Param("id") Long id,
                        @Param("workerId") Long workerId,
                        @Param("newStatus") String newStatus,
                        @Param("currentStatus") String currentStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET assigned_organization_id = :workerId, status = :newStatus WHERE request_id = :id AND status = :currentStatus",
            nativeQuery = true)
    int assignOrganization(@Param("id") Long id,
                           @Param("workerId") Long workerId,
                           @Param("newStatus") String newStatus,
                           @Param("currentStatus") String currentStatus);

    // Status transitions are guarded by the status the caller validated against
    // (B-4): if another transaction moved the row first, 0 rows match and the
    // service answers 409 instead of silently overwriting.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET status = :newStatus WHERE request_id = :id AND status = :expectedStatus",
            nativeQuery = true)
    int updateStatusNative(@Param("id") Long id,
                           @Param("newStatus") String newStatus,
                           @Param("expectedStatus") String expectedStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET status = :newStatus, completed_at = :completedAt "
                 + "WHERE request_id = :id AND status = :expectedStatus",
            nativeQuery = true)
    int updateStatusCompleted(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("completedAt") java.time.LocalDateTime completedAt,
                              @Param("expectedStatus") String expectedStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET status = :newStatus, cancelled_at = :cancelledAt "
                 + "WHERE request_id = :id AND status = :expectedStatus",
            nativeQuery = true)
    int updateStatusCancelled(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("cancelledAt") java.time.LocalDateTime cancelledAt,
                              @Param("expectedStatus") String expectedStatus);

    /**
     * Writes only priority_score, so the scheduler's recalculation neither
     * rewrites every column nor touches the columns other triggers watch.
     * Runs in its own transaction: one failing row does not abort the batch.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE help_requests SET priority_score = :score WHERE request_id = :id",
            nativeQuery = true)
    int updatePriorityScore(@Param("id") Long id, @Param("score") int score);

    /**
     * A declined request goes back on the queue: the provider columns are cleared
     * and the status returns to PENDING, guarded on ASSIGNED so two callers racing
     * from the same state cannot both win (B-4, GAP-1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET status = 'PENDING', assigned_volunteer_id = NULL, "
                 + "assigned_organization_id = NULL WHERE request_id = :id AND status = 'ASSIGNED'",
            nativeQuery = true)
    int releaseToPending(@Param("id") Long id);

    /**
     * The retry sweep's page: PENDING requests with no open assignment, most
     * important first. This ordering is the only place the priority score decides
     * what automatic matching looks at first (GAP-2) — on arrival, a request is
     * matched immediately and alone.
     */
    @Query(value = "SELECT r FROM HelpRequest r WHERE r.status = 'PENDING' "
                 + "AND NOT EXISTS (SELECT 1 FROM Assignment a WHERE a.requestId = r.id AND a.status = 'ASSIGNED') "
                 + "ORDER BY r.priorityScore DESC, r.id ASC")
    Page<HelpRequest> findUnassignedPendingByPriority(Pageable pageable);

    /** Raises the flag once; a request already flagged is left alone (GAP-1, GAP-2). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET needs_attention = true, needs_attention_at = :at, "
                 + "needs_attention_reason = :reason WHERE request_id = :id AND needs_attention = false",
            nativeQuery = true)
    int flagForAttention(@Param("id") Long id,
                         @Param("at") java.time.LocalDateTime at,
                         @Param("reason") String reason);

    /** Assignment answers the escalation, so the flag comes down with it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET needs_attention = false, needs_attention_at = NULL, "
                 + "needs_attention_reason = NULL WHERE request_id = :id AND needs_attention = true",
            nativeQuery = true)
    int clearAttention(@Param("id") Long id);

    long countByNeedsAttentionTrue();
}