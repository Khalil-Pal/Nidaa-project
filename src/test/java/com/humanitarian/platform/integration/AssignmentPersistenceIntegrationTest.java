package com.humanitarian.platform.integration;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.repository.AssignmentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Transactional
class AssignmentPersistenceIntegrationTest {

    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    void persistsAutomaticMaterialAssignmentWithoutHumanAssigner() {
        Long beneficiaryId = insertUser("BENEFICIARY");
        Long volunteerUserId = insertUser("VOLUNTEER");
        Long volunteerId = jdbc.queryForObject(
                "INSERT INTO volunteers (user_id) VALUES (?) RETURNING volunteer_id",
                Long.class,
                volunteerUserId);
        Long requestId = jdbc.queryForObject(
                "INSERT INTO help_requests "
                        + "(beneficiary_id, title, description, help_type, urgency_level, status) "
                        + "VALUES (?, 'Integration request', 'Assignment persistence', "
                        + "'FOOD', 'HIGH', 'PENDING') RETURNING request_id",
                Long.class,
                beneficiaryId);

        Assignment saved = assignmentRepository.saveAndFlush(Assignment.builder()
                .requestId(requestId)
                .volunteerId(volunteerId)
                .requestType("HELP_REQUEST")
                .assignmentSource("AUTO_GEO")
                .status("ASSIGNED")
                .assignedAt(LocalDateTime.now())
                .build());
        entityManager.clear();

        Assignment persisted = assignmentRepository.findById(saved.getId()).orElseThrow();
        assertNotNull(persisted.getVolunteerId());
        assertNull(persisted.getOrganizationId());
        assertNull(persisted.getPsychologistId());
        assertNull(persisted.getAssignedBy());
    }

    @Test
    void persistsAutomaticPsychologicalAssignmentWithoutVolunteerPlaceholder() {
        Long beneficiaryId = insertUser("BENEFICIARY");
        Long psychologistUserId = insertUser("PSYCHOLOGIST");
        Long psychologistId = jdbc.queryForObject(
                "INSERT INTO psychologists "
                        + "(user_id, specialization, is_verified, is_on_duty) "
                        + "VALUES (?, ARRAY['CRISIS']::psychological_category[], true, true) "
                        + "RETURNING psychologist_id",
                Long.class,
                psychologistUserId);
        Long requestId = jdbc.queryForObject(
                "INSERT INTO psychological_requests "
                        + "(beneficiary_id, support_type, category, urgency_level, status) "
                        + "VALUES (?, 'CRISIS', 'CRISIS', 'CRITICAL', 'PENDING') "
                        + "RETURNING request_id",
                Long.class,
                beneficiaryId);

        Assignment saved = assignmentRepository.saveAndFlush(Assignment.builder()
                .psychologicalRequestId(requestId)
                .psychologistId(psychologistId)
                .requestType("PSYCHOLOGICAL_REQUEST")
                .assignmentSource("AUTO_CRISIS")
                .status("ASSIGNED")
                .assignedAt(LocalDateTime.now())
                .build());
        entityManager.clear();

        Assignment persisted = assignmentRepository.findById(saved.getId()).orElseThrow();
        assertNotNull(persisted.getPsychologistId());
        assertNull(persisted.getVolunteerId());
        assertNull(persisted.getOrganizationId());
        assertNull(persisted.getAssignedBy());
    }

    private Long insertUser(String role) {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, phone, full_name, role) "
                        + "VALUES (?, 'integration-test', ?, 'Assignment Integration', ?) "
                        + "RETURNING user_id",
                Long.class,
                "assignment-" + suffix + "@example.test",
                "+100" + suffix.replace("-", "").substring(0, 10),
                role);
    }
}
