package com.humanitarian.platform.security;

import com.humanitarian.platform.controller.AdminController;
import com.humanitarian.platform.controller.AdminV1Controller;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import com.humanitarian.platform.service.AssignmentHistoryService;
import com.humanitarian.platform.service.HelpRequestService;
import com.humanitarian.platform.service.MatchingEvaluationService;
import com.humanitarian.platform.service.PriorityScoreService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AdminController.class, AdminV1Controller.class})
class AdminSecurityTest extends SecuritySliceTest {

    @MockBean private UserRepository userRepository;
    @MockBean private HelpRequestRepository helpRequestRepository;
    @MockBean private PsychologicalRequestRepository psychologicalRequestRepository;
    @MockBean private JdbcTemplate jdbcTemplate;
    @MockBean private HelpRequestService helpRequestService;
    @MockBean private VolunteerRepository volunteerRepository;
    @MockBean private MatchingEvaluationService matchingEvaluationService;
    @MockBean private AssignmentHistoryService assignmentHistoryService;
    @MockBean private AssignmentRepository assignmentRepository;
    @MockBean private PriorityScoreService priorityScoreService;

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void nonAdminCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());
        verify(userRepository, never()).findAll();
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void nonAdminCannotDeleteUser() throws Exception {
        User victim = user(5L, UserRole.VOLUNTEER);
        when(userRepository.findById(5L)).thenReturn(Optional.of(victim));

        mockMvc.perform(delete("/api/admin/users/5"))
                .andExpect(status().isForbidden());
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void nonAdminCannotAccessAdminDashboard() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/ranked"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanListUsers() throws Exception {
        when(userRepository.findAll()).thenReturn(List.of(user(1L, UserRole.ADMIN)));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCannotAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/dashboard/ranked"))
                .andExpect(status().isUnauthorized());
    }
}
