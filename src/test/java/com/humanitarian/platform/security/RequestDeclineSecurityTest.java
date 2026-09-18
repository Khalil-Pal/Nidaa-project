package com.humanitarian.platform.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.humanitarian.platform.controller.HelpRequestController;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import com.humanitarian.platform.service.AttentionService;
import com.humanitarian.platform.service.AutomaticAssignmentService;
import com.humanitarian.platform.service.ContactInfoService;
import com.humanitarian.platform.service.HelpRequestService;
import com.humanitarian.platform.service.ProviderResourceService;
import com.humanitarian.platform.service.RequestDeclineService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * GAP-1 through the real security config and the real {@link RequestDeclineService}
 * with repositories mocked: who may decline, what it does to the assignment, the
 * capacity and the request, that the rematch never offers it to a provider who
 * declined, and that the third decline escalates instead of looping.
 *
 * The request is 10, assigned to volunteer profile 20 (user 2). User 3 is another
 * volunteer, user 1 the beneficiary, user 99 an administrator.
 */
@WebMvcTest(HelpRequestController.class)
@Import(RequestDeclineService.class)
class RequestDeclineSecurityTest extends SecuritySliceTest {

    @MockBean private HelpRequestService helpRequestService;
    @MockBean private ContactInfoService contactInfoService;
    @MockBean private HelpRequestRepository helpRequestRepository;
    @MockBean private AssignmentRepository assignmentRepository;
    @MockBean private VolunteerRepository volunteerRepository;
    @MockBean private OrganizationRepository organizationRepository;
    @MockBean private ProviderResourceService providerResourceService;
    @MockBean private AutomaticAssignmentService automaticAssignmentService;
    @MockBean private AttentionService attention;

    private HelpRequest request;
    private Assignment assignment;

    @BeforeEach
    void fixtures() {
        request = HelpRequest.builder().id(10L).beneficiaryId(1L).title("Food for a family")
                .helpType("FOOD").peopleCount(4).urgencyLevel("HIGH").status("ASSIGNED")
                .assignedVolunteerId(20L).latitude(55.75).longitude(37.62).build();
        assignment = Assignment.builder().id(100L).requestId(10L).requestType("HELP_REQUEST")
                .volunteerId(20L).status("ASSIGNED").assignmentSource("AUTO_GEO")
                .resourceUserId(2L).resourceHelpType("FOOD").reservedCapacityAmount(4).build();
        when(helpRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(assignmentRepository.findFirstByRequestIdAndStatusOrderByAssignedAtDesc(10L, "ASSIGNED"))
                .thenReturn(Optional.of(assignment));
        when(volunteerRepository.findByUserId(2L)).thenReturn(Optional.of(
                Volunteer.builder().id(20L).user(user(2L, UserRole.VOLUNTEER)).build()));
        when(volunteerRepository.findByUserId(3L)).thenReturn(Optional.of(
                Volunteer.builder().id(30L).user(user(3L, UserRole.VOLUNTEER)).build()));
        when(organizationRepository.findByUserId(anyLong())).thenReturn(Optional.<Organization>empty());
        when(assignmentRepository.markCapacityRestored(eq(100L), any())).thenReturn(1);
        when(helpRequestRepository.releaseToPending(10L)).thenReturn(1);
        when(assignmentRepository.findAllByRequestIdAndStatus(10L, "DECLINED")).thenReturn(List.of());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(put("/api/help-requests/10/decline")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void theBeneficiaryCannotDeclineTheirOwnRequest() throws Exception {
        actingAs(1L, UserRole.BENEFICIARY);
        mockMvc.perform(put("/api/help-requests/10/decline")).andExpect(status().isForbidden());
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void aVolunteerWhoIsNotTheProviderSeesNoRequest() throws Exception {
        actingAs(3L, UserRole.VOLUNTEER);
        mockMvc.perform(put("/api/help-requests/10/decline")).andExpect(status().isNotFound());
        verify(assignmentRepository, never()).save(any());
        verify(helpRequestRepository, never()).releaseToPending(anyLong());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void decliningRestoresCapacityReleasesTheProviderAndRematches() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        when(assignmentRepository.countByRequestIdAndStatus(10L, "DECLINED")).thenReturn(1L);
        when(assignmentRepository.countByVolunteerIdAndStatus(20L, "ASSIGNED")).thenReturn(0L);
        when(automaticAssignmentService.assignNearestProvider(any(HelpRequest.class),
                any(AutomaticAssignmentService.ProviderExclusions.class))).thenReturn(true);

        mockMvc.perform(put("/api/help-requests/10/decline").param("reason", "Van broke down"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.requestId").value(10))
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.declineCount").value(1))
                .andExpect(jsonPath("$.data.reassigned").value(true))
                .andExpect(jsonPath("$.data.needsAttention").value(false));

        // the assignment is DECLINED, not CANCELLED, and carries the reason
        ArgumentCaptor<Assignment> saved = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(saved.capture());
        org.junit.jupiter.api.Assertions.assertEquals("DECLINED", saved.getValue().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getValue().getNotes().contains("Van broke down"));
        org.junit.jupiter.api.Assertions.assertNotNull(saved.getValue().getCompletedAt());
        // capacity restored exactly once, through the guarded UPDATE
        verify(assignmentRepository).markCapacityRestored(eq(100L), any());
        verify(providerResourceService).restoreReservation(any());
        verify(volunteerRepository).release(20L);
        verify(helpRequestRepository).releaseToPending(10L);
        // the beneficiary is told, the decliner is not
        verify(notifications).notify(eq(1L), eq("Your request is being matched again"), anyString(),
                eq("HELP_REQUEST"), eq(10L));
        verify(notifications, never()).notify(eq(2L), anyString(), anyString(), anyString(), anyLong());
        verify(attention, never()).flag(any(), anyString());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void theRematchExcludesEveryProviderWhoAlreadyDeclined() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        when(assignmentRepository.countByRequestIdAndStatus(10L, "DECLINED")).thenReturn(2L);
        when(assignmentRepository.findAllByRequestIdAndStatus(10L, "DECLINED")).thenReturn(List.of(
                Assignment.builder().id(100L).requestId(10L).volunteerId(20L).status("DECLINED").build(),
                Assignment.builder().id(101L).requestId(10L).organizationId(70L).status("DECLINED").build()));
        when(automaticAssignmentService.assignNearestProvider(any(HelpRequest.class),
                any(AutomaticAssignmentService.ProviderExclusions.class))).thenReturn(false);

        mockMvc.perform(put("/api/help-requests/10/decline")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reassigned").value(false));

        ArgumentCaptor<AutomaticAssignmentService.ProviderExclusions> excluded =
                ArgumentCaptor.forClass(AutomaticAssignmentService.ProviderExclusions.class);
        verify(automaticAssignmentService).assignNearestProvider(any(HelpRequest.class), excluded.capture());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(20L), excluded.getValue().volunteerIds());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(70L), excluded.getValue().organizationIds());
        // nobody took it: it is PENDING for the sweep, not flagged yet and not lost
        verify(attention, never()).flag(any(), anyString());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void theThirdDeclineEscalatesInsteadOfLooping() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        when(assignmentRepository.countByRequestIdAndStatus(10L, "DECLINED"))
                .thenReturn((long) RequestDeclineService.MAX_DECLINES);

        mockMvc.perform(put("/api/help-requests/10/decline")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.declineCount").value(RequestDeclineService.MAX_DECLINES))
                .andExpect(jsonPath("$.data.needsAttention").value(true));

        verify(attention).flag(any(HelpRequest.class), eq("3 providers declined this request"));
        verify(automaticAssignmentService, never()).assignNearestProvider(any(HelpRequest.class), any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void aRequestThatIsNoLongerAssignedCannotBeDeclined() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        request.setStatus("IN_PROGRESS");
        mockMvc.perform(put("/api/help-requests/10/decline")).andExpect(status().isBadRequest());
        verify(assignmentRepository, never()).save(any());
        verify(providerResourceService, never()).restoreReservation(any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void aLostRaceOnTheGuardedUpdateIsAConflict() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        when(helpRequestRepository.releaseToPending(10L)).thenReturn(0);
        mockMvc.perform(put("/api/help-requests/10/decline")).andExpect(status().isConflict());
        verify(automaticAssignmentService, never()).assignNearestProvider(any(HelpRequest.class), any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void aRequestWithNoOpenAssignmentIsNotFound() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        when(assignmentRepository.findFirstByRequestIdAndStatusOrderByAssignedAtDesc(10L, "ASSIGNED"))
                .thenReturn(Optional.empty());
        mockMvc.perform(put("/api/help-requests/10/decline")).andExpect(status().isNotFound());
    }
}
