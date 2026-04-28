package com.humanitarian.platform.service;

import com.humanitarian.platform.model.PasswordResetToken;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.PasswordResetTokenRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import org.springframework.stereotype.Service;

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
    private PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String senderEmail;

    // Step 1: Send reset code to email
    public String sendResetCode(String email) {
        String normalizedEmail = email.toLowerCase().trim();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("No account found with this email address."));

        if (!user.getIsActive()) {
            throw new BusinessException("This account is not active. Please contact support.");
        }

        // Clean up expired tokens first
        tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());

        // Delete any existing token for this email (user is requesting again)
        tokenRepository.deleteByEmail(normalizedEmail);

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

        return "Reset code sent to " + maskEmail(email);
    }

    // Step 2: Verify the code
    public boolean verifyCode(String email, String code) {
        String normalizedEmail = email.toLowerCase().trim();
        return tokenRepository.findByEmail(normalizedEmail)
                .map(token -> {
                    if (LocalDateTime.now().isAfter(token.getExpiresAt())) {
                        tokenRepository.deleteByEmail(normalizedEmail);
                        return false;
                    }
                    return token.getCode().equals(code.trim().toUpperCase());
                })
                .orElse(false);
    }

    // Step 3: Reset the password
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

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

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
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(user.getEmail());
            msg.setSubject("[Nidaa] Your Password Reset Code");
            msg.setText(
                    "Hello " + user.getFullName() + ",\n\n" +
                    "You requested a password reset for your Nidaa account.\n\n" +
                    "Your reset code is:\n\n" +
                    "    " + code + "\n\n" +
                    "This code expires in " + CODE_EXPIRY_MINUTES + " minutes.\n\n" +
                    "If you did not request this, please ignore this email.\n\n" +
                    "— The Nidaa Team\n" +
                    "supp0rtnidaa@yandex.ru"
            );
            mailSender.send(msg);
            log.info("Reset email sent to: {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send reset email: {}", e.getMessage());
        }
    }
}