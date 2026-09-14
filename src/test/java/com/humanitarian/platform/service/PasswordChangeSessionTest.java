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
        User user = account();
        when(tokenRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(token("owner@example.com", "ABC123")));
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("new-password")).thenReturn("hash");

        resetService.resetPassword("owner@example.com", "abc123", "new-password");

        verify(userRepository).save(user);
        org.junit.jupiter.api.Assertions.assertEquals("hash", user.getPasswordHash());
        org.junit.jupiter.api.Assertions.assertNotNull(user.getTokensValidFrom(), "sessions issued before now are cut off");
        verify(refreshTokenRepository).deleteByEmail("owner@example.com");
    }

    // -- S-9: reset codes may be guessed at most five times ---------------------

    @Test
    void fifthWrongResetCodeDiscardsTheToken() {
        PasswordResetToken token = token("owner@example.com", "ABC123");
        token.setAttempts(3);
        when(tokenRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(token));

        org.junit.jupiter.api.Assertions.assertFalse(resetService.verifyCode("owner@example.com", "WRONG1"));
        org.junit.jupiter.api.Assertions.assertEquals(4, token.getAttempts());
        verify(tokenRepository).save(token);

        org.junit.jupiter.api.Assertions.assertFalse(resetService.verifyCode("owner@example.com", "WRONG2"));
        verify(tokenRepository).deleteByEmail("owner@example.com");
    }

    @Test
    void correctResetCodeDoesNotCountAsAnAttempt() {
        PasswordResetToken token = token("owner@example.com", "ABC123");
        when(tokenRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(token));

        org.junit.jupiter.api.Assertions.assertTrue(resetService.verifyCode("owner@example.com", "abc123"));
        org.junit.jupiter.api.Assertions.assertEquals(0, token.getAttempts());
        verify(tokenRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void fifthWrongChangeCodeDiscardsTheToken() {
        when(userService.getCurrentUser()).thenReturn(account());
        PasswordResetToken token = token("CHANGE:owner@example.com", "ZZZ999");
        token.setAttempts(4);
        when(tokenRepository.findByEmail("CHANGE:owner@example.com")).thenReturn(Optional.of(token));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.humanitarian.platform.exception.BusinessException.class,
                () -> changeService.confirmPasswordChange("nope", "new-password"));

        verify(tokenRepository).deleteByEmail("CHANGE:owner@example.com");
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void confirmPasswordChangeInvalidatesAccessAndRefreshTokens() {
        User user = account();
        when(userService.getCurrentUser()).thenReturn(user);
        when(tokenRepository.findByEmail("CHANGE:owner@example.com")).thenReturn(Optional.of(token("CHANGE:owner@example.com", "ZZZ999")));
        when(passwordEncoder.encode("new-password")).thenReturn("hash");

        changeService.confirmPasswordChange("zzz999", "new-password");

        verify(userRepository).save(user);
        org.junit.jupiter.api.Assertions.assertEquals("hash", user.getPasswordHash());
        org.junit.jupiter.api.Assertions.assertNotNull(user.getTokensValidFrom());
        verify(refreshTokenRepository).deleteByEmail("owner@example.com");
    }
}
