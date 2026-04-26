package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.HelpRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // Native SQL — passes status as a bound parameter so stringtype=unspecified
    // handles the PostgreSQL enum cast at the driver level.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET assigned_volunteer_id = :workerId, status = :newStatus WHERE request_id = :id AND status = :currentStatus",
            nativeQuery = true)
    int assignVolunteer(@Param("id") Long id,
                        @Param("workerId") Long workerId,
                        @Param("newStatus") String newStatus,
                        @Param("currentStatus") String currentStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET assigned_organization_id = :workerId, status = :newStatus WHERE request_id = :id AND status = :currentStatus",
            nativeQuery = true)
    int assignOrganization(@Param("id") Long id,
                           @Param("workerId") Long workerId,
                           @Param("newStatus") String newStatus,
                           @Param("currentStatus") String currentStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE help_requests SET status = :newStatus WHERE request_id = :id",
            nativeQuery = true)
    int updateStatusNative(@Param("id") Long id, @Param("newStatus") String newStatus);
}