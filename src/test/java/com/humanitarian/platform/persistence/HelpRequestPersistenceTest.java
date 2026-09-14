package com.humanitarian.platform.persistence;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.HelpRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
class HelpRequestPersistenceTest extends PersistenceTestSupport {

    @Autowired private HelpRequestRepository helpRequestRepository;

    @Test
    void priorityScoreIsStoredAsTheApplicationComputedIt() {
        // V15 removed the trigger that used to overwrite this on insert.
        User beneficiary = newUser(UserRole.BENEFICIARY, "score@example.test");
        HelpRequest saved = newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "PENDING");   // fixture stores 50
        em.clear();

        assertEquals(50, em.find(HelpRequest.class, saved.getId()).getPriorityScore());
    }

    @Test
    void updatePriorityScoreWritesOnlyThatColumn() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "recalc@example.test");
        HelpRequest saved = newHelpRequest(beneficiary.getId(), "WATER", "LOW", "PENDING");
        em.clear();

        assertEquals(1, helpRequestRepository.updatePriorityScore(saved.getId(), 77));
        em.clear();
        HelpRequest reloaded = em.find(HelpRequest.class, saved.getId());

        assertEquals(77, reloaded.getPriorityScore());
        assertEquals("WATER", reloaded.getHelpType());
        assertEquals("PENDING", reloaded.getStatus());
    }

    @Test
    void priorityScoreOutsideTheCheckRangeIsRejected() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "range@example.test");
        HelpRequest saved = newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "PENDING");
        em.clear();

        assertThrows(DataIntegrityViolationException.class,
                () -> helpRequestRepository.updatePriorityScore(saved.getId(), 101));
    }

    @Test
    void pagedPendingQueryReturnsOnlyPendingRows() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "paged@example.test");
        newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "PENDING");
        newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "COMPLETED");
        em.clear();

        var page = helpRequestRepository.findByStatus("PENDING", PageRequest.of(0, 50));

        assertTrue(page.getContent().stream().allMatch(r -> "PENDING".equals(r.getStatus())));
        assertTrue(page.getContent().stream().anyMatch(r -> r.getBeneficiaryId().equals(beneficiary.getId())));
    }

    @Test
    void conditionalAssignmentClaimsOnlyAPendingRequest() {
        // The lock-free pattern: UPDATE ... WHERE status = 'PENDING' returns the
        // affected-row count, so the second of two racing providers gets 0.
        User beneficiary = newUser(UserRole.BENEFICIARY, "race-b@example.test");
        User volunteerUser = newUser(UserRole.VOLUNTEER, "race-v@example.test");
        Volunteer volunteer = em.persistAndFlush(Volunteer.builder().user(volunteerUser).rating(4.0).isAvailable(true).build());
        HelpRequest request = newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "PENDING");
        em.clear();

        int first = helpRequestRepository.assignVolunteer(request.getId(), volunteer.getId(), "ASSIGNED", "PENDING");
        int second = helpRequestRepository.assignVolunteer(request.getId(), volunteer.getId(), "ASSIGNED", "PENDING");
        em.clear();

        assertEquals(1, first);
        assertEquals(0, second);
        HelpRequest reloaded = em.find(HelpRequest.class, request.getId());
        assertEquals("ASSIGNED", reloaded.getStatus());
        assertEquals(volunteer.getId(), reloaded.getAssignedVolunteerId());
        assertNull(reloaded.getAssignedOrganizationId());
    }
}
