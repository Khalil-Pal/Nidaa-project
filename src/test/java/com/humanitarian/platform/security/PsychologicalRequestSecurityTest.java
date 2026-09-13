package com.humanitarian.platform.security;

import com.humanitarian.platform.controller.PsychologicalRequestController;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.service.AutomaticAssignmentService;
import com.humanitarian.platform.service.ContactInfoService;
import com.humanitarian.platform.service.CrisisDetectorService;
import com.humanitarian.platform.service.PsychologicalRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses the real {@link PsychologicalRequestService}; repositories are mocked.
 * Reads of records the caller may not see must come back as 404, never 403,
 * because confirming that a mental-health record exists is itself a disclosure.
 */
@WebMvcTest(PsychologicalRequestController.class)
@Import(PsychologicalRequestService.class)
class PsychologicalRequestSecurityTest extends SecuritySliceTest {

    private static final long OWNER_ID = 2L;
    private static final long OTHER_BENEFICIARY_ID = 3L;
    private static final long PSYCHOLOGIST_USER_ID = 20L;
    private static final long PSYCHOLOGIST_PROFILE_ID = 7L;
    private static final long OTHER_PSYCHOLOGIST_PROFILE_ID = 8L;

    @MockBean private ContactInfoService contactInfoService;
    @MockBean private PsychologicalRequestRepository psychologicalRequestRepository;
    @MockBean private JdbcTemplate jdbcTemplate;
    @MockBean private CrisisDetectorService crisisDetectorService;
    @MockBean private AssignmentRepository assignmentRepository;
    @MockBean private PsychologistRepository psychologistRepository;
    @MockBean private AutomaticAssignmentService automaticAssignmentService;

    private PsychologicalRequest storedRequest(long id, String status, Long assignedPsychologistId) {
        PsychologicalRequest r = PsychologicalRequest.builder()
                .id(id)
                .beneficiaryId(OWNER_ID)
                .supportType("INDIVIDUAL")
                .category("ANXIETY")
                .urgencyLevel("MEDIUM")
                .preferredFormat("CHAT")
                .description("I have not slept in a week")
                .status(status)
                .assignedPsychologistId(assignedPsychologistId)
                .build();
        when(psychologicalRequestRepository.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    private void psychologistProfile(long userId, long profileId) {
        Psychologist p = Psychologist.builder().id(profileId).isVerified(true).build();
        when(psychologistRepository.findByUserId(userId)).thenReturn(Optional.of(p));
    }

    // -- reading a single request ---------------------------------------------

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotReadAnotherUsersPsychRequest() throws Exception {
        actingAs(OTHER_BENEFICIARY_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "PENDING", null);

        mockMvc.perform(get("/api/psychological-requests/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCanReadOwnPsychRequest() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "PENDING", null);

        mockMvc.perform(get("/api/psychological-requests/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void psychologistCannotReadCaseAssignedToSomeoneElse() throws Exception {
        actingAs(PSYCHOLOGIST_USER_ID, UserRole.PSYCHOLOGIST);
        psychologistProfile(PSYCHOLOGIST_USER_ID, PSYCHOLOGIST_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", OTHER_PSYCHOLOGIST_PROFILE_ID);

        mockMvc.perform(get("/api/psychological-requests/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void assignedPsychologistCanReadOwnCase() throws Exception {
        actingAs(PSYCHOLOGIST_USER_ID, UserRole.PSYCHOLOGIST);
        psychologistProfile(PSYCHOLOGIST_USER_ID, PSYCHOLOGIST_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", PSYCHOLOGIST_PROFILE_ID);

        mockMvc.perform(get("/api/psychological-requests/1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReadAnyCase() throws Exception {
        actingAs(99L, UserRole.ADMIN);
        storedRequest(1L, "PENDING", null);

        mockMvc.perform(get("/api/psychological-requests/1"))
                .andExpect(status().isOk());
    }

    // -- closing a case -------------------------------------------------------

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void psychologistCannotCloseUnassignedCase() throws Exception {
        actingAs(PSYCHOLOGIST_USER_ID, UserRole.PSYCHOLOGIST);
        psychologistProfile(PSYCHOLOGIST_USER_ID, PSYCHOLOGIST_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", OTHER_PSYCHOLOGIST_PROFILE_ID);

        mockMvc.perform(put("/api/psychological-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isForbidden());
        verify(psychologicalRequestRepository, never()).updateStatusNative(anyLong(), anyString());
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void assignedPsychologistCanCloseOwnCase() throws Exception {
        actingAs(PSYCHOLOGIST_USER_ID, UserRole.PSYCHOLOGIST);
        psychologistProfile(PSYCHOLOGIST_USER_ID, PSYCHOLOGIST_PROFILE_ID);
        storedRequest(1L, "ASSIGNED", PSYCHOLOGIST_PROFILE_ID);
        when(psychologicalRequestRepository.updateStatusNative(1L, "COMPLETED")).thenReturn(1);

        mockMvc.perform(put("/api/psychological-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isOk());
        verify(psychologicalRequestRepository).updateStatusNative(eq(1L), eq("COMPLETED"));
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCanWithdrawOwnCaseButNotCloseIt() throws Exception {
        actingAs(OWNER_ID, UserRole.BENEFICIARY);
        storedRequest(1L, "ASSIGNED", PSYCHOLOGIST_PROFILE_ID);

        mockMvc.perform(put("/api/psychological-requests/1/status").param("status", "COMPLETED"))
                .andExpect(status().isForbidden());

        when(psychologicalRequestRepository.updateStatusNative(1L, "CANCELLED")).thenReturn(1);
        mockMvc.perform(put("/api/psychological-requests/1/status").param("status", "CANCELLED"))
                .andExpect(status().isOk());
        verify(psychologicalRequestRepository, never()).updateStatusNative(1L, "COMPLETED");
    }

    // -- role boundaries ------------------------------------------------------

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void volunteerCannotAccessPsychologicalEndpoints() throws Exception {
        mockMvc.perform(get("/api/psychological-requests/my")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/psychological-requests/1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/psychological-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"ANXIETY\",\"description\":\"x\"}"))
                .andExpect(status().isForbidden());
        verify(psychologicalRequestRepository, never()).findById(anyLong());
        verify(psychologicalRequestRepository, never()).findByBeneficiaryId(anyLong());
    }

    @Test
    void anonymousCannotAccessPsychologicalEndpoints() throws Exception {
        mockMvc.perform(get("/api/psychological-requests/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/psychological-requests/my")).andExpect(status().isUnauthorized());
    }
}
