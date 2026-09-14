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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET status = :newStatus WHERE request_id = :id",
            nativeQuery = true)
    int updateStatusNative(@Param("id") Long id, @Param("newStatus") String newStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET status = :newStatus, completed_at = :completedAt WHERE request_id = :id",
            nativeQuery = true)
    int updateStatusCompleted(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("completedAt") java.time.LocalDateTime completedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET status = :newStatus, cancelled_at = :cancelledAt WHERE request_id = :id",
            nativeQuery = true)
    int updateStatusCancelled(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("cancelledAt") java.time.LocalDateTime cancelledAt);

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
}