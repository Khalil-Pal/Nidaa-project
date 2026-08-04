package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    List<Assignment> findByRequestId(Long requestId);
    List<Assignment> findByRequestIdOrderByAssignedAtAsc(Long requestId);
    List<Assignment> findByPsychologicalRequestIdOrderByAssignedAtAsc(Long psychologicalRequestId);
    List<Assignment> findByVolunteerId(Long volunteerId);
    List<Assignment> findByVolunteerIdOrderByAssignedAtDesc(Long volunteerId);
    List<Assignment> findByOrganizationId(Long organizationId);
    List<Assignment> findByOrganizationIdOrderByAssignedAtDesc(Long organizationId);
    List<Assignment> findByPsychologistIdOrderByAssignedAtDesc(Long psychologistId);
    List<Assignment> findAllByOrderByAssignedAtDesc();
    Optional<Assignment> findByRequestIdAndStatus(Long requestId, String status);
    Optional<Assignment> findFirstByRequestIdOrderByAssignedAtDesc(Long requestId);
    Optional<Assignment> findFirstByRequestIdAndStatusOrderByAssignedAtDesc(Long requestId, String status);
    Optional<Assignment> findFirstByPsychologicalRequestIdOrderByAssignedAtDesc(Long psychologicalRequestId);
    Optional<Assignment> findFirstByPsychologicalRequestIdAndStatusOrderByAssignedAtDesc(
            Long psychologicalRequestId, String status);
    List<Assignment> findByVolunteerIdAndStatus(Long volunteerId, String status);
    long countByVolunteerIdAndStatus(Long volunteerId, String status);
    long countByOrganizationIdAndStatus(Long organizationId, String status);
    long countByPsychologistIdAndStatus(Long psychologistId, String status);

    @Query("SELECT (COUNT(assignment) > 0) FROM Assignment assignment "
            + "WHERE assignment.resourceUserId = :userId "
            + "AND assignment.resourceHelpType = :helpType "
            + "AND assignment.status = 'ASSIGNED' "
            + "AND assignment.reservedCapacityAmount IS NOT NULL "
            + "AND assignment.capacityRestoredAt IS NULL")
    boolean hasActiveCapacityReservation(@Param("userId") Long userId,
                                         @Param("helpType") String helpType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Assignment assignment "
            + "SET assignment.capacityRestoredAt = :restoredAt "
            + "WHERE assignment.id = :assignmentId "
            + "AND assignment.reservedCapacityAmount IS NOT NULL "
            + "AND assignment.capacityRestoredAt IS NULL")
    int markCapacityRestored(@Param("assignmentId") Long assignmentId,
                             @Param("restoredAt") LocalDateTime restoredAt);
}
