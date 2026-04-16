package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {
    Optional<Report> findByAssignmentId(Long assignmentId);
    List<Report> findByVolunteerId(Long volunteerId);
    List<Report> findByBeneficiaryRatingIsNotNull();
}