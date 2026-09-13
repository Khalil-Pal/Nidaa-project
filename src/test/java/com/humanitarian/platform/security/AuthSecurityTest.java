package com.humanitarian.platform.security;

import com.humanitarian.platform.controller.AuthController;
import com.humanitarian.platform.controller.PasswordResetController;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.PasswordResetTokenRepository;
import com.humanitarian.platform.repository.PendingRegistrationRepository;
import com.humanitarian.platform.repository.RefreshTokenRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.AuthService;
import com.humanitarian.platform.service.PasswordResetService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuthController.class, PasswordResetController.class})
@Import({AuthService.class, PasswordResetService.class})
class AuthSecurityTest extends SecuritySliceTest {

    private static final String KNOWN = "known@example.com";
    private static final String UNKNOWN = "nobody@example.com";
    private static final String RIGHT_PASSWORD = "correct-horse";

    @MockBean private UserRepository userRepository;
    @MockBean private RefreshTokenRepository refreshTokenRepository;
    @MockBean private PendingRegistrationRepository pendingRegistrationRepository;
    @MockBean private PasswordResetTokenRepository passwordResetTokenRepository;
    @MockBean private EntityManager entityManager;

    @Autowired private PasswordEncoder passwordEncoder;

    private static String registration(String role) {
        return """
                {"fullName":"Test Person","email":"person@example.com",
                 "password":"correct-horse","phone":"+10000000","role":"%s"}
                """.formatted(role);
    }

    private static String login(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    /** Registers a real account with the mocked repository and user-details service. */
    private User knownAccount(UserRole role, boolean active, boolean locked) {
        User user = user(7L, role);
        user.setEmail(KNOWN);
        user.setPasswordHash(passwordEncoder.encode(RIGHT_PASSWORD));
        user.setIsActive(active);
        user.setIsLocked(locked);
        when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(user));
        UserDetails details = org.springframework.security.core.userdetails.User
                .withUsername(KNOWN)
                .password(user.getPasswordHash())
                .authorities(List.of())
                .disabled(!active)
                .accountLocked(locked)
                .build();
        when(userDetailsService.loadUserByUsername(KNOWN)).thenReturn(details);
        when(userDetailsService.loadUserByUsername(UNKNOWN))
                .thenThrow(new UsernameNotFoundException("User not found with email: " + UNKNOWN));
        return user;
    }

    // -- S-1 ------------------------------------------------------------------

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

    // -- S-8: login -----------------------------------------------------------

    @Test
    void unknownEmailAndWrongPasswordAreIndistinguishable() throws Exception {
        knownAccount(UserRole.BENEFICIARY, true, false);

        MvcResult unknown = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(UNKNOWN, "whatever")))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(KNOWN, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(not(containsStringIgnoringCase("attempt"))))
                .andReturn();

        assertEquals(stripTimestamp(unknown.getResponse().getContentAsString()),
                stripTimestamp(wrongPassword.getResponse().getContentAsString()));
    }

    @Test
    void correctPasswordStillLogsIn() throws Exception {
        knownAccount(UserRole.BENEFICIARY, true, false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(KNOWN, RIGHT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void accountStateIsOnlyRevealedAfterCorrectPassword() throws Exception {
        knownAccount(UserRole.VOLUNTEER, false, false);

        // wrong password: nothing about the account leaks
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(KNOWN, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsStringIgnoringCase("approval"))));

        // right password: the owner is told why they cannot proceed
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(KNOWN, RIGHT_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsStringIgnoringCase("pending admin approval")));
    }

    // -- S-8: forgot password -------------------------------------------------

    @Test
    void forgotPasswordAnswersIdenticallyForKnownAndUnknownEmail() throws Exception {
        knownAccount(UserRole.BENEFICIARY, true, false);
        when(userRepository.findByEmail(UNKNOWN)).thenReturn(Optional.empty());

        MvcResult known = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + KNOWN + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult unknown = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + UNKNOWN + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(known.getResponse().getContentAsString(), unknown.getResponse().getContentAsString());
        // a code was issued only for the real account
        verify(passwordResetTokenRepository).save(any());
    }

    private static String stripTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\",", "");
    }
}
