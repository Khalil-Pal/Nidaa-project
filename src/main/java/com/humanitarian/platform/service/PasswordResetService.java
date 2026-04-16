package com.humanitarian.platform.service;

import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    // Store codes in memory: email -> {code, expiry}
    // In production you'd store this in a DB table
    private final Map<String, CodeEntry> codeStore = new ConcurrentHashMap<>();

    // Code characters: uppercase, lowercase, digits, symbols
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789@#$!%&";
    private static final int CODE_LENGTH = 6;
    private static final int CODE_EXPIRY_MINUTES = 15;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String senderEmail;

    // Step 1: Send reset code to email
    public String sendResetCode(String email) {
        User user = userRepository.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new RuntimeException("No account found with this email address."));

        if (!user.getIsActive()) {
            throw new RuntimeException("This account is not active. Please contact support.");
        }

        // Generate 6-char code with letters, numbers and symbols
        String code = generateCode();
        codeStore.put(email.toLowerCase(), new CodeEntry(code, LocalDateTime.now().plusMinutes(CODE_EXPIRY_MINUTES)));

        log.info("Password reset code for {}: {}", email, code);

        // Send email
        sendResetEmail(user, code);

        return "Reset code sent to " + maskEmail(email);
    }

    // Step 2: Verify the code
    public boolean verifyCode(String email, String code) {
        String key = email.toLowerCase().trim();
        CodeEntry entry = codeStore.get(key);
        if (entry == null) return false;
        if (LocalDateTime.now().isAfter(entry.expiry)) {
            codeStore.remove(key);
            return false;
        }
        return entry.code.equals(code.trim().toUpperCase());
    }

    // Step 3: Reset the password
    public void resetPassword(String email, String code, String newPassword) {
        if (!verifyCode(email, code)) {
            throw new RuntimeException("Invalid or expired reset code.");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new RuntimeException("Password must be at least 6 characters.");
        }

        User user = userRepository.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new RuntimeException("User not found."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        codeStore.remove(email.toLowerCase());

        log.info("Password reset successfully for: {}", email);
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
            log.warn("Mail not configured. Reset code: {}", code);
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
            // Don't throw - code is still generated, just not emailed
        }
    }

    private static class CodeEntry {
        final String code;
        final LocalDateTime expiry;
        CodeEntry(String code, LocalDateTime expiry) {
            this.code = code;
            this.expiry = expiry;
        }
    }
}