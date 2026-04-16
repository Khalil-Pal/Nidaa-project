package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    List<Assignment> findByRequestId(Long requestId);
    List<Assignment> findByVolunteerId(Long volunteerId);
    List<Assignment> findByOrganizationId(Long organizationId);
    Optional<Assignment> findByRequestIdAndStatus(Long requestId, String status);
    List<Assignment> findByVolunteerIdAndStatus(Long volunteerId, String status);
    long countByVolunteerIdAndStatus(Long volunteerId, String status);
}