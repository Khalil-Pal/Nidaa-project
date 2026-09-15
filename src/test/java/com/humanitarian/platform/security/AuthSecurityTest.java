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
import com.humanitarian.platform.service.EmailTemplateService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuthController.class, PasswordResetController.class})
@Import({AuthService.class, PasswordResetService.class, EmailTemplateService.class})
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

    /**
     * F-3: the landing page's images are .webp now. The static-file rule lists
     * extensions explicitly, so an unlisted one is answered 401 by the entry
     * point instead of being served (the first run of the converted page lost
     * its hero image exactly this way).
     */
    @Test
    void webpImagesArePublicLikeTheOtherStaticFiles() throws Exception {
        mockMvc.perform(get("/nidaa-hero.webp")).andExpect(status().isOk());
        mockMvc.perform(get("/images/help-types/food-help.webp")).andExpect(status().isOk());
        mockMvc.perform(get("/nidaa-hero.bmp")).andExpect(status().isUnauthorized());
    }

    /** DEP-2: the API explorer is public; a protected endpoint called from it still needs a token. */
    @Test
    void apiExplorerIsReachableWithoutAToken() throws Exception {
        // no springdoc in this slice, so "permitted" shows as 404 rather than 401
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
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
                .andExpect(jsonPath("$.data.email").value("person@example.com"));

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

        assertEquals(unknown.getResponse().getContentAsString(),
                wrongPassword.getResponse().getContentAsString());
    }

    @Test
    void correctPasswordStillLogsIn() throws Exception {
        knownAccount(UserRole.BENEFICIARY, true, false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(KNOWN, RIGHT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
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

    @Test
    void lockoutIsPerEmailAndAddressSoOneAttackerCannotLockEveryoneOut() throws Exception {
        knownAccount(UserRole.BENEFICIARY, true, false);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .with(r -> { r.setRemoteAddr("203.0.113.9"); return r; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(login(KNOWN, "wrong-password")));
        }
        // the attacker's address is now locked for this email
        mockMvc.perform(post("/api/auth/login")
                        .with(r -> { r.setRemoteAddr("203.0.113.9"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(KNOWN, RIGHT_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsStringIgnoringCase("too many failed")));

        // the real owner, from their own address, still gets in
        mockMvc.perform(post("/api/auth/login")
                        .with(r -> { r.setRemoteAddr("198.51.100.4"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(KNOWN, RIGHT_PASSWORD)))
                .andExpect(status().isOk());
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
}
