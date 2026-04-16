package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.PsychologicalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
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
}