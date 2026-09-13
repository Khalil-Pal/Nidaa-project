package com.humanitarian.platform.security;

import com.humanitarian.platform.controller.HelpRequestController;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import com.humanitarian.platform.service.AutomaticAssignmentService;
import com.humanitarian.platform.service.ContactInfoService;
import com.humanitarian.platform.service.GeoMatchingService;
import com.humanitarian.platform.service.HelpRequestService;
import com.humanitarian.platform.service.PriorityScoreService;
import com.humanitarian.platform.service.ProviderResourceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses the real {@link HelpRequestService} so ownership and transition rules are
 * exercised through HTTP; only repositories and collaborators are mocked.
 */
@WebMvcTest(HelpRequestController.class)
@Import(HelpRequestService.class)
class HelpRequestSecurityTest extends SecuritySliceTest {

    private static final long OWNER_ID = 2L;
    private static final long OTHER_BENEFICIARY_ID = 3L;
    private static final long VOLUNTEER_USER_ID = 10L;
    private static final long VOLUNTEER_PROFILE_ID = 5L;
    private static final long OTHER_VOLUNTEER_PROFILE_ID = 6L;

    @MockBean private ContactInfoService contactInfoService;
    @MockBean private JdbcTemplate jdbcTemplate;
    @MockBean private HelpRequestRepository helpRequestRepository;
    @MockBean private UserRepository userRepository;
    @MockBean private PriorityScoreService priorityScoreService;
    @MockBean private GeoMatchingService geoMatchingService;
    @MockBean private AssignmentRepository assignmentRepository;
    @MockBean private VolunteerRepository volunteerRepository;
    @MockBean private OrganizationRepository organizationRepository;
    @MockBean private AutomaticAssignmentService automaticAssignmentService;
    @MockBean private ProviderResourceService providerResourceService;

    private HelpRequest storedRequest(long id, String status, Long assignedVolunteerId) {
        HelpRequest r = HelpRequest.builder()
                .id(id)
                .beneficiaryId(OWNER_ID)
                .title("Food for a family of four")
                .description("Food")
                .helpType("FOOD")
                .urgencyLevel("HIGH")
                .status(status)
                .assignedVolunteerId(assignedVolunteerId)
                .build();
        when(helpRequestRepository.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    private void volunteerProfile(long userId, long profileId) {
        Volunteer v = Volunteer.builder().id(profileId).isAvailable(true).build();
        when(volunteerRepository.findByUserId(userId)).thenReturn(Optional.of(v));
    }

    // -- authentication -------------------------------------------------------

    @Test
    void anonymousCannotAccessAnyProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/help-requests/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/help-requests")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/help-requests/my")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/help-requests/1/status").param("status", "CANCELLED"))
                .andExpect(status().isUnauthorized());
        verify(helpRequestRepository, never()).findById(anyLong());
    }

    @Test
    void expiredTokenIsTreatedAsUnauthenticatedNotForbidden() throws Exception {
        mockMvc.perform(get("/api/help-requests/1")
                        .header("Authorization", "Bearer " + expiredAccessToken("owner@example.com")))
                .andExpect(status().isUnauthorized());
        verify(helpRequestRepository, never()).findById(anyLong());
    }

    // -- listing --------------------------------------------------------------

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotListAllHelpRequests() throws Exception {
        mockMvc.perform(get("/api/help-requests")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/help-requests/pending")).andExpect(status().isForbidden());
        verify(helpRequestRepository, never()).findAll(any(Pageable.class));
        verify(helpRequestRepository, never()).findByStatusOrderByPriorityScoreDesc(anyString());
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void psychologistCannotListHelpRequests() throws Exception {
        mockMvc.perform(get("/api/help-requests")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void volunteerCanListHelpRequests() throws Exception {
        when(helpRequestRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));
        mockMvc.perform(get("/api/help-requests")).andExpect(status().isOk());
    }

    // -- reading a single request ---------------------------------------------

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotReadAnotherUsersHelpRequest() throws Exception {
        actingAs(OTHER_BENEFICIARY_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "PENDING", null);

        mockMvc.perform(get("/api/help-requests/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCanReadOwnHelpRequest() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "PENDING", null);

        mockMvc.perform(get("/api/help-requests/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void volunteerCannotReadRequestAssignedToSomeoneElse() throws Exception {
        actingAs(VOLUNTEER_USER_ID, UserRole.VOLUNTEER);
        volunteerProfile(VOLUNTEER_USER_ID, VOLUNTEER_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", OTHER_VOLUNTEER_PROFILE_ID);

        mockMvc.perform(get("/api/help-requests/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void assignedVolunteerCanReadOwnAssignedRequest() throws Exception {
        actingAs(VOLUNTEER_USER_ID, UserRole.VOLUNTEER);
        volunteerProfile(VOLUNTEER_USER_ID, VOLUNTEER_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", VOLUNTEER_PROFILE_ID);

        mockMvc.perform(get("/api/help-requests/1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void filerCanReadRequestTheyFiled() throws Exception {
        actingAs(VOLUNTEER_USER_ID, UserRole.VOLUNTEER);
        storedRequest(1L, "PENDING", null).setFiledByUserId(VOLUNTEER_USER_ID);

        mockMvc.perform(get("/api/help-requests/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filedByUserId").value(VOLUNTEER_USER_ID));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReadAnyRequest() throws Exception {
        actingAs(99L, UserRole.ADMIN);
        storedRequest(1L, "ASSIGNED", OTHER_VOLUNTEER_PROFILE_ID);

        mockMvc.perform(get("/api/help-requests/1"))
                .andExpect(status().isOk());
    }

    // -- status changes -------------------------------------------------------

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void volunteerCannotCompleteUnassignedRequest() throws Exception {
        actingAs(VOLUNTEER_USER_ID, UserRole.VOLUNTEER);
        volunteerProfile(VOLUNTEER_USER_ID, VOLUNTEER_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", OTHER_VOLUNTEER_PROFILE_ID);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isForbidden());
        verify(helpRequestRepository, never()).updateStatusCompleted(anyLong(), anyString(), any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void volunteerCanCompleteOwnAssignedRequest() throws Exception {
        actingAs(VOLUNTEER_USER_ID, UserRole.VOLUNTEER);
        volunteerProfile(VOLUNTEER_USER_ID, VOLUNTEER_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", VOLUNTEER_PROFILE_ID);
        when(helpRequestRepository.updateStatusCompleted(eq(1L), eq("COMPLETED"), any())).thenReturn(1);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isOk());
        verify(helpRequestRepository).updateStatusCompleted(eq(1L), eq("COMPLETED"), any());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotCompleteOwnRequest() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "ASSIGNED", VOLUNTEER_PROFILE_ID);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isForbidden());
        verify(helpRequestRepository, never()).updateStatusCompleted(anyLong(), anyString(), any());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCanCancelOwnRequest() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "PENDING", null);
        when(helpRequestRepository.updateStatusCancelled(eq(1L), eq("CANCELLED"), any())).thenReturn(1);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "CANCELLED"))
                .andExpect(status().isOk());
        verify(helpRequestRepository).updateStatusCancelled(eq(1L), eq("CANCELLED"), any());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotCancelAnotherUsersRequest() throws Exception {
        actingAs(OTHER_BENEFICIARY_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "PENDING", null);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "CANCELLED"))
                .andExpect(status().isForbidden());
        verify(helpRequestRepository, never()).updateStatusCancelled(anyLong(), anyString(), any());
    }
}
