package com.humanitarian.platform.service;

import com.humanitarian.platform.model.PasswordResetToken;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.PasswordResetTokenRepository;
import com.humanitarian.platform.repository.RefreshTokenRepository;
import com.humanitarian.platform.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S-7: both password paths must end every existing session. */
@ExtendWith(MockitoExtension.class)
class PasswordChangeSessionTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EntityManager entityManager;
    @Mock private UserService userService;

    @InjectMocks private PasswordResetService resetService;
    @InjectMocks private PasswordChangeService changeService;

    private static User account() {
        return User.builder().id(7L).email("owner@example.com").role(UserRole.BENEFICIARY)
                .isActive(true).build();
    }

    private static PasswordResetToken token(String email, String code) {
        return PasswordResetToken.builder().email(email).code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(10)).build();
    }

    @Test
    void resetPasswordInvalidatesAccessAndRefreshTokens() {
        when(tokenRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(token("owner@example.com", "ABC123")));
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(account()));
        when(passwordEncoder.encode("new-password")).thenReturn("hash");

        resetService.resetPassword("owner@example.com", "abc123", "new-password");

        verify(userRepository).updatePassword(eq(7L), eq("hash"), any(LocalDateTime.class));
        verify(refreshTokenRepository).deleteByEmail("owner@example.com");
    }

    @Test
    void confirmPasswordChangeInvalidatesAccessAndRefreshTokens() {
        when(userService.getCurrentUser()).thenReturn(account());
        when(tokenRepository.findByEmail("CHANGE:owner@example.com")).thenReturn(Optional.of(token("CHANGE:owner@example.com", "ZZZ999")));
        when(passwordEncoder.encode("new-password")).thenReturn("hash");

        changeService.confirmPasswordChange("zzz999", "new-password");

        verify(userRepository).updatePassword(eq(7L), eq("hash"), any(LocalDateTime.class));
        verify(refreshTokenRepository).deleteByEmail("owner@example.com");
    }
}
