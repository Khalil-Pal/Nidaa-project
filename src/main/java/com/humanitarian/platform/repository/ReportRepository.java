package com.humanitarian.platform.repository;

import com.humanitarian.platform.dto.RatingSummary;
import com.humanitarian.platform.model.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {
    Optional<Report> findByAssignmentId(Long assignmentId);
    boolean existsByAssignmentId(Long assignmentId);
    List<Report> findByVolunteerId(Long volunteerId);
    List<Report> findByBeneficiaryRatingIsNotNull();

    /** Source of volunteers.total_completed_requests and volunteers.rating, recomputed in full (AGG-1). */
    @Query("SELECT new com.humanitarian.platform.dto.RatingSummary(COUNT(r), AVG(r.beneficiaryRating)) "
         + "FROM Report r WHERE r.volunteerId = :volunteerId")
    RatingSummary summarizeForVolunteer(@Param("volunteerId") Long volunteerId);
}
