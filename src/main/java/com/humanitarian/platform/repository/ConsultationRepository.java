package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConsultationRepository extends JpaRepository<Consultation, Long> {
    List<Consultation> findByPsychologistId(Long psychologistId);
    List<Consultation> findByBeneficiaryId(Long beneficiaryId);
    // one record per case (uq_consultations_psych_request, V20)
    Optional<Consultation> findByPsychologicalRequestId(Long requestId);
    boolean existsByPsychologicalRequestId(Long requestId);
    List<Consultation> findByIsCrisisTrue();
    long countByPsychologistId(Long psychologistId);
}
