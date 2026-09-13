package com.humanitarian.platform.service;

import com.humanitarian.platform.model.PasswordResetToken;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.PasswordResetTokenRepository;
import com.humanitarian.platform.repository.RefreshTokenRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789@#$!%&";
    private static final int CODE_LENGTH = 6;
    private static final int CODE_EXPIRY_MINUTES = 15;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String senderEmail;

    /** The only message the forgot-password endpoint ever returns. */
    public static final String RESET_REQUEST_RESPONSE =
            "If an account exists for that address, a reset code has been sent.";

    // Step 1: Send reset code to email
    @Transactional
    public void sendResetCode(String email) {
        String normalizedEmail = email == null ? "" : email.toLowerCase().trim();

        // Unknown or inactive addresses return silently: the caller always
        // receives RESET_REQUEST_RESPONSE, so the endpoint cannot be used to
        // find out who has an account.
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            log.info("Password reset requested for an unknown or inactive address");
            return;
        }

        // Clean up expired tokens first
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());

        // Delete any existing token for this email (user is requesting again)
        tokenRepository.deleteByEmail(normalizedEmail);
        entityManager.flush(); // ensure deletes are committed before insert

        // Generate new code and persist it in DB
        String code = generateCode();
        PasswordResetToken token = PasswordResetToken.builder()
                .email(normalizedEmail)
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(CODE_EXPIRY_MINUTES))
                .createdAt(LocalDateTime.now())
                .build();
        tokenRepository.save(token);

        sendResetEmail(user, code);
    }

    /** Wrong codes tolerated per token before it is discarded (S-9). */
    public static final int MAX_CODE_ATTEMPTS = 5;

    // Step 2: Verify the code. Every wrong code counts against the token, and
    // the fifth one deletes it, so the 6-character code cannot be brute-forced
    // within its 15-minute lifetime.
    @Transactional
    public boolean verifyCode(String email, String code) {
        String normalizedEmail = email == null ? "" : email.toLowerCase().trim();
        String submitted = code == null ? "" : code.trim().toUpperCase();
        return tokenRepository.findByEmail(normalizedEmail)
                .map(token -> {
                    if (LocalDateTime.now().isAfter(token.getExpiresAt())) {
                        tokenRepository.deleteByEmail(normalizedEmail);
                        return false;
                    }
                    if (token.getCode().equals(submitted)) {
                        return true;
                    }
                    int attempts = (token.getAttempts() == null ? 0 : token.getAttempts()) + 1;
                    if (attempts >= MAX_CODE_ATTEMPTS) {
                        tokenRepository.deleteByEmail(normalizedEmail);
                        log.warn("Reset token discarded after {} wrong codes", attempts);
                    } else {
                        token.setAttempts(attempts);
                        tokenRepository.save(token);
                    }
                    return false;
                })
                .orElse(false);
    }

    // Step 3: Reset the password
    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        if (!verifyCode(email, code)) {
            throw new BusinessException("Invalid or expired reset code.");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException("Password must be at least 6 characters.");
        }

        String normalizedEmail = email.toLowerCase().trim();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        // New password, and every existing session ends: access tokens issued
        // before now are rejected by the JWT filter, refresh tokens are deleted.
        // A password reset is the canonical "my account was compromised" step,
        // so leaving an attacker's tokens alive would defeat its purpose.
        userRepository.updatePassword(user.getId(), passwordEncoder.encode(newPassword),
                LocalDateTime.now()); // native SQL
        refreshTokenRepository.deleteByEmail(normalizedEmail);

        // Remove the used token
        tokenRepository.deleteByEmail(normalizedEmail);

        log.info("Password reset successfully for: {}", normalizedEmail);
    }

    private String generateCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return "***" + email.substring(at);
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private void sendResetEmail(User user, String code) {
    if (mailSender == null || senderEmail == null || senderEmail.isBlank()) {
        log.warn("Mail not configured — reset code NOT logged for security reasons.");
        return;
    }
    try {
        // Get HTML template from service
        EmailTemplateService templateService = new EmailTemplateService();
        String htmlContent = templateService.generatePasswordResetEmail(
                user.getFullName(),
                user.getEmail(),
                code
        );

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(senderEmail);
        helper.setTo(user.getEmail());
        helper.setSubject("[Nidaa] Your Password Reset Code 🔐");
        helper.setText(htmlContent, true); // true = isHtml

        mailSender.send(message);
        log.info("HTML reset email sent to: {}", user.getEmail());
    } catch (Exception e) {
        log.error("Failed to send reset email: {}", e.getMessage());
    }
}
}