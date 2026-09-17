package com.humanitarian.platform.repository;

import com.humanitarian.platform.dto.KeyCount;
import com.humanitarian.platform.dto.PsychologistCaseLoad;
import com.humanitarian.platform.model.PsychologicalRequest;
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
public interface PsychologicalRequestRepository extends JpaRepository<PsychologicalRequest, Long> {

    List<PsychologicalRequest> findByBeneficiaryId(Long beneficiaryId);
    List<PsychologicalRequest> findByAssignedPsychologistId(Long psychologistId);
    List<PsychologicalRequest> findByStatus(String status);
    Page<PsychologicalRequest> findByStatusAndAssignedPsychologistIdIsNull(String status, Pageable pageable);
    List<PsychologicalRequest> findByCategory(String category);
    List<PsychologicalRequest> findByPreferredFormat(String format);
    List<PsychologicalRequest> findByIsCrisisTrue();
    long countByAssignedPsychologistIdAndStatus(Long psychologistId, String status);

    // -- statistics: aggregate in the database, never load the table (Q-2) --
    long countByCreatedAtAfter(LocalDateTime since);
    long countByStatusAndCompletedAtAfter(String status, LocalDateTime since);

    @Query("SELECT new com.humanitarian.platform.dto.KeyCount(r.status, COUNT(r)) FROM PsychologicalRequest r GROUP BY r.status")
    List<KeyCount> countGroupedByStatus();

    /** Open cases per psychologist in one grouped query; psychologists with none are absent (Q-1). */
    @Query("SELECT new com.humanitarian.platform.dto.PsychologistCaseLoad(r.assignedPsychologistId, COUNT(r)) "
         + "FROM PsychologicalRequest r "
         + "WHERE r.status = :status AND r.assignedPsychologistId IS NOT NULL "
         + "GROUP BY r.assignedPsychologistId")
    List<PsychologistCaseLoad> caseLoadByPsychologist(@Param("status") String status);

    // Native SQL — assigns using psychologist_id (FK to psychologists table)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE psychological_requests SET assigned_psychologist_id = :psychologistId, status = :newStatus WHERE request_id = :id AND status = :currentStatus",
            nativeQuery = true)
    int assignPsychologist(@Param("id") Long id,
                           @Param("psychologistId") Long psychologistId,
                           @Param("newStatus") String newStatus,
                           @Param("currentStatus") String currentStatus);

    // Guarded by the status the caller validated against (B-4); 0 rows means someone else moved it first
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE psychological_requests SET status = :newStatus WHERE request_id = :id AND status = :expectedStatus",
            nativeQuery = true)
    int updateStatusNative(@Param("id") Long id,
                           @Param("newStatus") String newStatus,
                           @Param("expectedStatus") String expectedStatus);

    // Completion also stamps completed_at, which the statistics read (CS-1)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE psychological_requests SET status = :newStatus, completed_at = :completedAt "
                 + "WHERE request_id = :id AND status = :expectedStatus",
            nativeQuery = true)
    int updateStatusCompleted(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("completedAt") LocalDateTime completedAt,
                              @Param("expectedStatus") String expectedStatus);
}
