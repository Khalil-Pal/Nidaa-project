package com.humanitarian.platform.security;

import com.humanitarian.platform.controller.AuthController;
import com.humanitarian.platform.repository.PendingRegistrationRepository;
import com.humanitarian.platform.repository.RefreshTokenRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.AuthService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(AuthService.class)
class AuthSecurityTest extends SecuritySliceTest {

    @MockBean private UserRepository userRepository;
    @MockBean private RefreshTokenRepository refreshTokenRepository;
    @MockBean private PendingRegistrationRepository pendingRegistrationRepository;
    @MockBean private EntityManager entityManager;

    private static String registration(String role) {
        return """
                {"fullName":"Test Person","email":"person@example.com",
                 "password":"correct-horse","phone":"+10000000","role":"%s"}
                """.formatted(role);
    }

    @Test
    void registrationRejectsAdminRole() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This role cannot be self-registered."));

        verify(pendingRegistrationRepository, never()).save(any());
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    void registrationStillAcceptsBeneficiaryRole() throws Exception {
        when(userRepository.existsByEmail("person@example.com")).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("beneficiary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("person@example.com"));

        verify(pendingRegistrationRepository).save(any());
    }
}
