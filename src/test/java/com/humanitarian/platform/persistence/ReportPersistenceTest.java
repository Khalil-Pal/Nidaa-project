package com.humanitarian.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Report;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.ReportRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * R-1 against the real schema: V19's UNIQUE(assignment_id) lets the database
 * refuse a second report when two requests race past the service's check, and
 * the rating CHECK still holds 1..5.
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
class ReportPersistenceTest extends PersistenceTestSupport {

    @Autowired private ReportRepository reportRepository;

    private Assignment completedAssignment(String suffix) {
        User beneficiary = newUser(UserRole.BENEFICIARY, "report-bene-" + suffix + "@example.test");
        User volunteerUser = newUser(UserRole.VOLUNTEER, "report-vol-" + suffix + "@example.test");
        Volunteer volunteer = em.persistAndFlush(Volunteer.builder().user(volunteerUser).isAvailable(true).build());
        HelpRequest request = newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "COMPLETED");
        return em.persistAndFlush(Assignment.builder()
                .requestId(request.getId())
                .requestType("HELP_REQUEST")
                .volunteerId(volunteer.getId())
                .assignedBy(volunteerUser.getId())
                .assignmentSource("MANUAL")
                .status("COMPLETED")
                .assignedAt(LocalDateTime.now().minusHours(2))
                .completedAt(LocalDateTime.now())
                .build());
    }

    @Test
    void exactlyOneReportPerAssignmentIsEnforcedByTheDatabase() {
        Assignment a = completedAssignment("unique");
        reportRepository.saveAndFlush(Report.builder().assignmentId(a.getId()).volunteerId(a.getVolunteerId()).description("first").build());

        assertTrue(reportRepository.existsByAssignmentId(a.getId()));
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () ->
                reportRepository.saveAndFlush(Report.builder().assignmentId(a.getId()).volunteerId(a.getVolunteerId()).description("second").build()));
        assertTrue(String.valueOf(ex.getMostSpecificCause().getMessage()).contains("uq_reports_assignment"),
                "the V19 constraint is what refused it: " + ex.getMostSpecificCause().getMessage());
    }

    @Test
    void ratingStaysInsideOneToFiveAndMayBeAbsent() {
        Assignment a = completedAssignment("rating");
        Report r = reportRepository.saveAndFlush(Report.builder().assignmentId(a.getId()).volunteerId(a.getVolunteerId()).description("delivered").build());
        assertEquals(null, r.getBeneficiaryRating(), "no rating until the beneficiary gives one");

        r.setBeneficiaryRating(6);
        assertThrows(DataIntegrityViolationException.class, () -> reportRepository.saveAndFlush(r));
    }
}
