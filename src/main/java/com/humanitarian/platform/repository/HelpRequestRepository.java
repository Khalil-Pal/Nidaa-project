package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.HelpRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface HelpRequestRepository extends JpaRepository<HelpRequest, Long> {
    List<HelpRequest> findByBeneficiaryId(Long beneficiaryId);
    List<HelpRequest> findByStatus(String status);
    List<HelpRequest> findByHelpType(String helpType);
    List<HelpRequest> findByUrgencyLevel(String urgencyLevel);
    List<HelpRequest> findByStatusOrderByPriorityScoreDesc(String status);
    List<HelpRequest> findByAssignedVolunteerId(Long volunteerId);
    List<HelpRequest> findByAssignedOrganizationId(Long organizationId);
    long countByStatus(String status);

    @Query(value = "SELECT * FROM help_requests h WHERE " +
            "(6371 * acos(cos(radians(:lat)) * cos(radians(h.latitude)) * " +
            "cos(radians(h.longitude) - radians(:lng)) + " +
            "sin(radians(:lat)) * sin(radians(h.latitude)))) <= :radius " +
            "AND h.status = 'PENDING'", nativeQuery = true)
    List<HelpRequest> findNearbyPendingRequests(
            @Param("lat") double latitude,
            @Param("lng") double longitude,
            @Param("radius") double radiusKm);
}