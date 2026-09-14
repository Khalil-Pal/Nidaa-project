package com.humanitarian.platform.security;

import com.humanitarian.platform.controller.PsychologistController;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.service.PsychologistDutyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UX-2: only a psychologist can read or change their own duty state, through the real security chain. */
@WebMvcTest(PsychologistController.class)
@Import(PsychologistDutyService.class)
class PsychologistDutySecurityTest extends SecuritySliceTest {

    @MockBean private PsychologistRepository psychologistRepository;
    @MockBean private PsychologicalRequestRepository psychologicalRequestRepository;

    private Psychologist myProfile(boolean onDuty, boolean verified) {
        User me = User.builder().id(20L).email("psy@example.test").role(UserRole.PSYCHOLOGIST).isActive(true).build();
        when(userService.getCurrentUser()).thenReturn(me);
        Psychologist p = Psychologist.builder().id(7L).user(me).isOnDuty(onDuty).isVerified(verified).build();
        when(psychologistRepository.findByUserId(20L)).thenReturn(Optional.of(p));
        when(psychologistRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(psychologicalRequestRepository.countByAssignedPsychologistIdAndStatus(7L, "ASSIGNED")).thenReturn(2L);
        return p;
    }

    @Test
    void anonymousIs401() throws Exception {
        mockMvc.perform(get("/api/psychologists/me/duty")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"VOLUNTEER"})
    void otherRolesAreRefusedBeforeTheServiceRuns() throws Exception {
        mockMvc.perform(get("/api/psychologists/me/duty")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/psychologists/me/duty").contentType(MediaType.APPLICATION_JSON).content("{\"onDuty\":true}"))
                .andExpect(status().isForbidden());
        verify(psychologistRepository, never()).findByUserId(any());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void adminsCannotFlipSomeoneElsesDutyThroughThisEndpoint() throws Exception {
        mockMvc.perform(put("/api/psychologists/me/duty").contentType(MediaType.APPLICATION_JSON).content("{\"onDuty\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"PSYCHOLOGIST"})
    void psychologistReadsAndChangesTheirOwnDuty() throws Exception {
        Psychologist p = myProfile(false, true);

        mockMvc.perform(get("/api/psychologists/me/duty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onDuty").value(false))
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.openCases").value(2));

        mockMvc.perform(put("/api/psychologists/me/duty").contentType(MediaType.APPLICATION_JSON).content("{\"onDuty\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onDuty").value(true))
                .andExpect(jsonPath("$.data.psychologistId").value(7));
        verify(psychologistRepository).save(p);
    }

    @Test
    @WithMockUser(roles = {"PSYCHOLOGIST"})
    void missingFlagIsAValidationError() throws Exception {
        myProfile(false, true);
        mockMvc.perform(put("/api/psychologists/me/duty").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.onDuty").value("onDuty is required"));
        verify(psychologistRepository, never()).save(any());
    }

    @Test
    @WithMockUser(roles = {"PSYCHOLOGIST"})
    void psychologistWithoutAProfileRowGets404() throws Exception {
        User me = User.builder().id(21L).email("new-psy@example.test").role(UserRole.PSYCHOLOGIST).isActive(true).build();
        when(userService.getCurrentUser()).thenReturn(me);
        when(psychologistRepository.findByUserId(21L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/psychologists/me/duty")).andExpect(status().isNotFound());
    }
}
