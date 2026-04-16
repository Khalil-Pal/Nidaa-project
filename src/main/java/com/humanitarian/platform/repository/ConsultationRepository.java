package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ConsultationRepository extends JpaRepository<Consultation, Long> {
    List<Consultation> findByPsychologistId(Long psychologistId);
    List<Consultation> findByBeneficiaryId(Long beneficiaryId);
    List<Consultation> findByPsychologicalRequestId(Long requestId);
    List<Consultation> findByIsCrisisTrue();
    long countByPsychologistId(Long psychologistId);
}