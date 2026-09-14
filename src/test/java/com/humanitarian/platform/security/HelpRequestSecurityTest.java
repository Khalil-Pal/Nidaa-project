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

    // -- token revocation (S-7) -----------------------------------------------

    private String freshTokenFor(String email, java.time.LocalDateTime tokensValidFrom) {
        NidaaUserDetails details = new NidaaUserDetails(email, "{noop}x", true, true,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BENEFICIARY")),
                tokensValidFrom);
        when(userDetailsService.loadUserByUsername(email)).thenReturn(details);
        return jwtUtils.generateToken(email);
    }

    @Test
    void tokenIssuedBeforePasswordChangeIsRejected() throws Exception {
        String token = freshTokenFor("owner@example.com",
                java.time.LocalDateTime.now().plusMinutes(5));  // password changed "after" this token

        mockMvc.perform(get("/api/help-requests/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verify(helpRequestRepository, never()).findById(anyLong());
    }

    @Test
    void tokenIssuedAfterPasswordChangeIsAccepted() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "PENDING", null);
        String token = freshTokenFor("owner@example.com",
                java.time.LocalDateTime.now().minusMinutes(5));

        mockMvc.perform(get("/api/help-requests/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void tokenForDeactivatedAccountIsRejected() throws Exception {
        NidaaUserDetails disabled = new NidaaUserDetails("gone@example.com", "{noop}x", false, true,
                List.of(), null);
        when(userDetailsService.loadUserByUsername("gone@example.com")).thenReturn(disabled);
        String token = jwtUtils.generateToken("gone@example.com");

        mockMvc.perform(get("/api/help-requests/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // -- stored XSS (S-2) -----------------------------------------------------

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void titleWithMarkupIsRefusedBeforeItIsStored() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        String payload = "{\"title\":\"<img src=x onerror=alert(1)>\",\"helpType\":\"FOOD\",\"urgencyLevel\":\"HIGH\"}";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/help-requests")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.title").exists());
        verify(helpRequestRepository, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void overlongTitleAndDescriptionAreRefused() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        String payload = "{\"title\":\"" + "t".repeat(201) + "\",\"description\":\"" + "d".repeat(4001)
                + "\",\"helpType\":\"FOOD\",\"urgencyLevel\":\"HIGH\"}";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/help-requests")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.title").exists())
                .andExpect(jsonPath("$.details.description").exists());
    }

    @Test
    void everyResponseCarriesAContentSecurityPolicy() throws Exception {
        mockMvc.perform(get("/api/help-requests/1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Security-Policy", org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("default-src 'self'"),
                                org.hamcrest.Matchers.containsString("connect-src 'self'"),
                                org.hamcrest.Matchers.containsString("frame-ancestors 'none'"),
                                // F-5: no inline scripts or handlers anywhere, so script-src is strict
                                org.hamcrest.Matchers.containsString("script-src 'self' https://cdn.jsdelivr.net;"),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("script-src 'self' 'unsafe-inline'")))));
    }

    // -- input validation (B-2) -----------------------------------------------

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void unknownHelpTypeAndUrgencyAreRefusedWithFieldMessages() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        String payload = "{\"title\":\"Groceries\",\"helpType\":\"GROCERIES\",\"urgencyLevel\":\"ASAP\"}";

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/help-requests")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.helpType").exists())
                .andExpect(jsonPath("$.details.urgencyLevel").exists());
        verify(helpRequestRepository, never()).save(any());
    }

    // -- listing --------------------------------------------------------------

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotListAllHelpRequests() throws Exception {
        mockMvc.perform(get("/api/help-requests")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/help-requests/pending")).andExpect(status().isForbidden());
        verify(helpRequestRepository, never()).findAll(any(Pageable.class));
        verify(helpRequestRepository, never()).findByStatus(anyString(), any(Pageable.class));
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
        verify(helpRequestRepository, never()).updateStatusCompleted(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void volunteerCanCompleteOwnAssignedRequest() throws Exception {
        actingAs(VOLUNTEER_USER_ID, UserRole.VOLUNTEER);
        volunteerProfile(VOLUNTEER_USER_ID, VOLUNTEER_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", VOLUNTEER_PROFILE_ID);
        when(helpRequestRepository.updateStatusCompleted(eq(1L), eq("COMPLETED"), any(), eq("ASSIGNED"))).thenReturn(1);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isOk());
        verify(helpRequestRepository).updateStatusCompleted(eq(1L), eq("COMPLETED"), any(), eq("ASSIGNED"));
        verify(adminAudit, never()).record(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotCompleteOwnRequest() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "ASSIGNED", VOLUNTEER_PROFILE_ID);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isForbidden());
        verify(helpRequestRepository, never()).updateStatusCompleted(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCanCancelOwnRequest() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "PENDING", null);
        when(helpRequestRepository.updateStatusCancelled(eq(1L), eq("CANCELLED"), any(), eq("PENDING"))).thenReturn(1);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "CANCELLED"))
                .andExpect(status().isOk());
        verify(helpRequestRepository).updateStatusCancelled(eq(1L), eq("CANCELLED"), any(), eq("PENDING"));
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void filerCanCancelRequestTheyFiled() throws Exception {
        actingAs(VOLUNTEER_USER_ID, UserRole.VOLUNTEER);
        storedRequest(1L, "PENDING", null).setFiledByUserId(VOLUNTEER_USER_ID);
        when(helpRequestRepository.updateStatusCancelled(eq(1L), eq("CANCELLED"), any(), eq("PENDING"))).thenReturn(1);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "CANCELLED"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void filerCannotCompleteRequestTheyFiled() throws Exception {
        actingAs(VOLUNTEER_USER_ID, UserRole.VOLUNTEER);
        volunteerProfile(VOLUNTEER_USER_ID, VOLUNTEER_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", OTHER_VOLUNTEER_PROFILE_ID).setFiledByUserId(VOLUNTEER_USER_ID);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isForbidden());
        verify(helpRequestRepository, never()).updateStatusCompleted(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCompleteAnyAssignedRequest() throws Exception {
        actingAs(99L, UserRole.ADMIN);
        storedRequest(1L, "ASSIGNED", OTHER_VOLUNTEER_PROFILE_ID);
        when(helpRequestRepository.updateStatusCompleted(eq(1L), eq("COMPLETED"), any(), eq("ASSIGNED"))).thenReturn(1);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isOk());
        // C-4: an administrator overriding a request's status is written to activity_logs
        verify(adminAudit).record(eq("REQUEST_STATUS_CHANGED"), eq("HELP_REQUEST"), eq(1L), any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void statusUpdateThatLosesTheRaceIs409() throws Exception {
        actingAs(VOLUNTEER_USER_ID, UserRole.VOLUNTEER);
        volunteerProfile(VOLUNTEER_USER_ID, VOLUNTEER_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", VOLUNTEER_PROFILE_ID);
        // the row was ASSIGNED when read, but the guarded UPDATE matched nothing (B-4)
        when(helpRequestRepository.updateStatusCompleted(eq(1L), eq("COMPLETED"), any(), eq("ASSIGNED"))).thenReturn(0);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Reload")));
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void repeatingATransitionSomeoneElseAlreadyMadeIs409() throws Exception {
        actingAs(99L, UserRole.ADMIN);
        storedRequest(1L, "COMPLETED", VOLUNTEER_PROFILE_ID);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This request is already COMPLETED."));
        verify(helpRequestRepository, never()).updateStatusCompleted(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotCancelAnotherUsersRequest() throws Exception {
        actingAs(OTHER_BENEFICIARY_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "PENDING", null);

        mockMvc.perform(put("/api/help-requests/1/status").param("status", "CANCELLED"))
                .andExpect(status().isForbidden());
        verify(helpRequestRepository, never()).updateStatusCancelled(anyLong(), anyString(), any(), anyString());
    }
}
