package com.humanitarian.platform.repository;

import com.humanitarian.platform.dto.RatingSummary;
import com.humanitarian.platform.model.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** Source of psychologists.consultation_count and psychologists.rating, recomputed in full (AGG-1). */
    @Query("SELECT new com.humanitarian.platform.dto.RatingSummary(COUNT(c), AVG(c.rating)) "
         + "FROM Consultation c WHERE c.psychologistId = :psychologistId")
    RatingSummary summarizeForPsychologist(@Param("psychologistId") Long psychologistId);
}
