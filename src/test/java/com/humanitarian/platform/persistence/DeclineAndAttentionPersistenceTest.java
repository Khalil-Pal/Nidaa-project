package com.humanitarian.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.ProviderResource;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.ProviderResourceRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * GAP-1 and GAP-2 against the real schema: V23's attention columns and their
 * guarded UPDATEs, the guarded capacity restore that makes a decline give back
 * exactly what it reserved and no more, the DECLINED assignment rows the rematch
 * excludes on, and the retry sweep's query — PENDING requests with no open
 * assignment, highest priority score first.
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
class DeclineAndAttentionPersistenceTest extends PersistenceTestSupport {

    @Autowired private HelpRequestRepository helpRequestRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private ProviderResourceRepository providerResourceRepository;

    private Volunteer newVolunteer(String suffix) {
        User user = newUser(UserRole.VOLUNTEER, "decline-vol-" + suffix + "@example.test");
        return em.persistAndFlush(Volunteer.builder().user(user).isAvailable(true).availabilityPreference(true).build());
    }

    private Assignment openAssignment(HelpRequest request, Volunteer volunteer, Integer reserved) {
        return em.persistAndFlush(Assignment.builder()
                .requestId(request.getId())
                .requestType("HELP_REQUEST")
                .volunteerId(volunteer.getId())
                .assignmentSource("AUTO_GEO")
                .status("ASSIGNED")
                .assignedAt(LocalDateTime.now().minusHours(1))
                .resourceUserId(reserved == null ? null : volunteer.getUser().getId())
                .resourceHelpType(reserved == null ? null : "FOOD")
                .reservedCapacityAmount(reserved)
                .build());
    }

    @Test
    void theGuardedRestoreFiresOnceSoADeclineGivesBackExactlyWhatItReserved() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "decline-bene-capacity@example.test");
        HelpRequest request = newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "ASSIGNED");
        Volunteer volunteer = newVolunteer("capacity");
        ProviderResource resource = em.persistAndFlush(ProviderResource.builder()
                .userId(volunteer.getUser().getId()).helpType("FOOD")
                .capacityMode("NUMERIC").capacityAmount(6).build());
        Assignment assignment = openAssignment(request, volunteer, 4);
        LocalDateTime restoredAt = LocalDateTime.now();

        assertEquals(1, assignmentRepository.markCapacityRestored(assignment.getId(), restoredAt),
                "the first restore wins the guard");
        assertEquals(0, assignmentRepository.markCapacityRestored(assignment.getId(), restoredAt),
                "a second attempt changes nothing, so capacity cannot be given back twice");

        // the service adds the reserved amount back once, which is what the guard protects
        resource.setCapacityAmount(resource.getCapacityAmount() + assignment.getReservedCapacityAmount());
        providerResourceRepository.saveAndFlush(resource);
        em.clear();
        assertEquals(10, em.find(ProviderResource.class, resource.getId()).getCapacityAmount());
        assertNotNull(em.find(Assignment.class, assignment.getId()).getCapacityRestoredAt());
    }

    @Test
    void aDeclineReturnsTheRequestToTheQueueAndLeavesTheDeclinerOnRecord() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "decline-bene-queue@example.test");
        HelpRequest request = newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "ASSIGNED");
        Volunteer volunteer = newVolunteer("queue");
        request.setAssignedVolunteerId(volunteer.getId());
        em.persistAndFlush(request);
        Assignment assignment = openAssignment(request, volunteer, null);

        assertEquals(1, helpRequestRepository.releaseToPending(request.getId()));
        assertEquals(0, helpRequestRepository.releaseToPending(request.getId()), "guarded on ASSIGNED (B-4)");
        assignment.setStatus("DECLINED");
        assignment.setCompletedAt(LocalDateTime.now());
        assignmentRepository.saveAndFlush(assignment);
        em.clear();

        HelpRequest reloaded = em.find(HelpRequest.class, request.getId());
        assertEquals("PENDING", reloaded.getStatus());
        assertNull(reloaded.getAssignedVolunteerId(), "the provider columns are cleared with the status");
        assertNull(reloaded.getAssignedOrganizationId());
        assertFalse(reloaded.getNeedsAttention(), "one decline is not an escalation");

        List<Assignment> declined = assignmentRepository.findAllByRequestIdAndStatus(request.getId(), "DECLINED");
        assertEquals(List.of(volunteer.getId()), declined.stream().map(Assignment::getVolunteerId).toList(),
                "who declined is on record, which is what the rematch excludes on");
        assertEquals(1, assignmentRepository.countByRequestIdAndStatus(request.getId(), "DECLINED"));
    }

    @Test
    void theAttentionFlagIsRaisedOnceAndClearedByAssignment() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "decline-bene-attention@example.test");
        HelpRequest request = newHelpRequest(beneficiary.getId(), "WATER", "MEDIUM", "PENDING");
        LocalDateTime at = LocalDateTime.of(2026, 9, 17, 12, 0);

        assertEquals(1, helpRequestRepository.flagForAttention(request.getId(), at, "3 providers declined this request"));
        assertEquals(0, helpRequestRepository.flagForAttention(request.getId(), at.plusHours(1), "again"),
                "already flagged: no second notification storm");
        em.clear();
        HelpRequest flagged = em.find(HelpRequest.class, request.getId());
        assertTrue(flagged.getNeedsAttention());
        assertEquals(at, flagged.getNeedsAttentionAt());
        assertEquals("3 providers declined this request", flagged.getNeedsAttentionReason());
        assertEquals(1, helpRequestRepository.countByNeedsAttentionTrue());

        assertEquals(1, helpRequestRepository.clearAttention(request.getId()));
        assertEquals(0, helpRequestRepository.clearAttention(request.getId()));
        em.clear();
        HelpRequest cleared = em.find(HelpRequest.class, request.getId());
        assertFalse(cleared.getNeedsAttention());
        assertNull(cleared.getNeedsAttentionAt());
        assertNull(cleared.getNeedsAttentionReason());
    }

    @Test
    void theSweepSeesUnassignedPendingRequestsHighestPriorityFirst() {
        User beneficiary = newUser(UserRole.BENEFICIARY, "decline-bene-sweep@example.test");
        HelpRequest low = newHelpRequest(beneficiary.getId(), "FOOD", "LOW", "PENDING");
        low.setPriorityScore(15);
        em.persistAndFlush(low);
        HelpRequest urgent = newHelpRequest(beneficiary.getId(), "MEDICAL", "CRITICAL", "PENDING");
        urgent.setPriorityScore(92);
        em.persistAndFlush(urgent);
        HelpRequest middling = newHelpRequest(beneficiary.getId(), "WATER", "MEDIUM", "PENDING");
        middling.setPriorityScore(48);
        em.persistAndFlush(middling);

        // one PENDING request that still has an open assignment: the sweep must skip it
        HelpRequest held = newHelpRequest(beneficiary.getId(), "SHELTER", "HIGH", "PENDING");
        held.setPriorityScore(99);
        em.persistAndFlush(held);
        openAssignment(held, newVolunteer("sweep"), null);
        em.flush();
        em.clear();

        List<Long> seen = helpRequestRepository.findUnassignedPendingByPriority(PageRequest.of(0, 50))
                .getContent().stream().map(HelpRequest::getId).toList();
        assertEquals(List.of(urgent.getId(), middling.getId(), low.getId()), seen,
                "priority order, and the one with an open assignment is not in the sweep");
    }
}
