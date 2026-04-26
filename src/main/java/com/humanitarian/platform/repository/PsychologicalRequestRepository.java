package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.PsychologicalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PsychologicalRequestRepository extends JpaRepository<PsychologicalRequest, Long> {

    List<PsychologicalRequest> findByBeneficiaryId(Long beneficiaryId);
    List<PsychologicalRequest> findByAssignedPsychologistId(Long psychologistId);
    List<PsychologicalRequest> findByStatus(String status);
    List<PsychologicalRequest> findByStatusAndAssignedPsychologistIdIsNull(String status);
    List<PsychologicalRequest> findByCategory(String category);
    List<PsychologicalRequest> findByPreferredFormat(String format);
    List<PsychologicalRequest> findByIsCrisisTrue();

    // Native SQL — assigns using psychologist_id (FK to psychologists table)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE psychological_requests SET assigned_psychologist_id = :psychologistId, status = :newStatus WHERE request_id = :id AND status = :currentStatus",
            nativeQuery = true)
    int assignPsychologist(@Param("id") Long id,
                           @Param("psychologistId") Long psychologistId,
                           @Param("newStatus") String newStatus,
                           @Param("currentStatus") String currentStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE psychological_requests SET status = :newStatus WHERE request_id = :id",
            nativeQuery = true)
    int updateStatusNative(@Param("id") Long id, @Param("newStatus") String newStatus);
}