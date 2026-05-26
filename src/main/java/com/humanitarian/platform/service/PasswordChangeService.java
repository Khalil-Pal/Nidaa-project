package com.humanitarian.platform.service;

import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.model.PasswordResetToken;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.PasswordResetTokenRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private static final String CHARS         = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int    CODE_LENGTH   = 8;
    private static final int    EXPIRY_MINUTES = 10;

    // We reuse the password_reset_tokens table but prefix the email
    // with "CHANGE:" so it doesn't collide with actual password reset codes
    private static final String PREFIX = "CHANGE:";

    @Autowired private UserService               userService;
    @Autowired private UserRepository            userRepository;
    @Autowired private PasswordResetTokenRepository tokenRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PasswordEncoder           passwordEncoder;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String senderEmail;

    /**
     * Step 1 — Verify current password and send a confirmation code to email.
     */
    @Transactional
    public String requestPasswordChange(String currentPassword, String newPassword) {
        User user = userService.getCurrentUser();

        // Validate current password
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("Current password is incorrect.");
        }

        // Validate new password
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException("New password must be at least 6 characters.");
        }

        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("New password must be different from your current password.");
        }

        // Clean up expired tokens
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());

        // Delete any existing change-request code for this user
        String key = PREFIX + user.getEmail();
        tokenRepository.deleteByEmail(key);
        entityManager.flush(); // ensure deletes are committed before insert

        // Generate and save the verification code
        String code = generateCode();
        PasswordResetToken token = PasswordResetToken.builder()
                .email(key)
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(EXPIRY_MINUTES))
                .createdAt(LocalDateTime.now())
                .build();
        tokenRepository.save(token);

        sendVerificationEmail(user, code);

        return "Verification code sent to " + maskEmail(user.getEmail()) +
                ". It expires in " + EXPIRY_MINUTES + " minutes.";
    }

    /**
     * Step 2 — Verify the code and apply the new password.
     */
    @Transactional
    public String confirmPasswordChange(String code, String newPassword) {
        User user = userService.getCurrentUser();
        String key = PREFIX + user.getEmail();

        PasswordResetToken token = tokenRepository.findByEmail(key)
                .orElseThrow(() -> new BusinessException(
                        "No pending password change request. Please start the process again."));

        if (LocalDateTime.now().isAfter(token.getExpiresAt())) {
            tokenRepository.deleteByEmail(key);
            throw new BusinessException("Verification code has expired. Please start again.");
        }

        if (!token.getCode().equalsIgnoreCase(code.trim())) {
            throw new BusinessException("Incorrect verification code.");
        }

        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException("New password must be at least 6 characters.");
        }

        // Apply the new password
        userRepository.updatePassword(user.getId(), passwordEncoder.encode(newPassword)); // native SQL

        // Remove the used code
        tokenRepository.deleteByEmail(key);

        log.info("Password changed successfully for: {}", user.getEmail());
        return "Password changed successfully.";
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

    private void sendVerificationEmail(User user, String code) {
        if (mailSender == null || senderEmail == null || senderEmail.isBlank()) {
            log.warn("Mail not configured — cannot send password change verification code.");
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(senderEmail);
            msg.setTo(user.getEmail());
            msg.setSubject("[Nidaa] Confirm Your Password Change");
            msg.setText(
                    "Hello " + user.getFullName() + ",\n\n" +
                            "We received a request to change your Nidaa account password.\n\n" +
                            "Your verification code is:\n\n" +
                            "    " + code + "\n\n" +
                            "This code expires in " + EXPIRY_MINUTES + " minutes.\n\n" +
                            "If you did not request this change, please ignore this email — " +
                            "your password will remain unchanged.\n\n" +
                            "— The Nidaa Team\n" +
                            "supp0rtnidaa@yandex.ru"
            );
            mailSender.send(msg);
            log.info("Password change verification email sent to: {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send verification email: {}", e.getMessage());
            throw new BusinessException("Failed to send email: " + e.getMessage());
        }
    }
}