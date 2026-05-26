package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.AuthResponse;
import com.humanitarian.platform.dto.LoginRequest;
import com.humanitarian.platform.dto.RegisterRequest;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.model.PendingRegistration;
import com.humanitarian.platform.model.RefreshToken;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.PendingRegistrationRepository;
import com.humanitarian.platform.repository.RefreshTokenRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.security.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private static final Set<UserRole> ROLES_REQUIRING_APPROVAL = Set.of(
            UserRole.VOLUNTEER,
            UserRole.PSYCHOLOGIST,
            UserRole.ORGANIZATION
    );

    // Brute force protection
    private final Map<String, FailedAttempt> failedAttempts = new ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS    = 5;
    private static final int LOCKOUT_MINUTES = 15;

    @Autowired private UserRepository                userRepository;
    @Autowired private PasswordEncoder               passwordEncoder;
    @Autowired private JwtUtils                      jwtUtils;
    @Autowired private RefreshTokenRepository        refreshTokenRepository;
    @Autowired private PendingRegistrationRepository pendingRepository;
    @Autowired private AuthenticationManager         authenticationManager;
    @Autowired(required = false) private JavaMailSender mailSender;
    @Autowired private EntityManager entityManager;

    @Value("${spring.mail.username:}")
    private String senderEmail;

    @Value("${nidaa.admin.email:supp0rtnidaa@yandex.ru}")
    private String adminEmail;

    // ── REGISTRATION ─────────────────────────────────────────────────────────

    /**
     * Step 1: Validate data, save to pending_registrations, send verification code.
     * The user is NOT created in the users table yet.
     */
    @Transactional
    public Map<String, Object> register(RegisterRequest request) {
        logger.info("Registration request: {}", request.getEmail());

        String email = request.getEmail().toLowerCase().trim();

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("This email is already registered.");
        }

        // Clean up expired pending registrations
        pendingRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        // Delete any previous pending for this email (allow resend)
        pendingRepository.deleteByEmail(email);

        String code = generateCode(8);

        PendingRegistration pending = PendingRegistration.builder()
                .email(email)
                .fullName(request.getFullName().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .phone(request.getPhone())
                .role(request.getRole().name())
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .createdAt(LocalDateTime.now())
                .build();
        pendingRepository.save(pending);

        sendRegistrationEmail(email, request.getFullName().trim(), code);

        logger.info("Registration verification code sent to: {}", email);
        return Map.of(
                "message", "Verification code sent to " + maskEmail(email) + ". Enter the code to complete registration.",
                "email", email
        );
    }

    /**
     * Step 2: Verify the code and create the real user account.
     */
    @Transactional
    public AuthResponse verifyRegistration(String email, String code) {
        String normalizedEmail = email.toLowerCase().trim();

        PendingRegistration pending = pendingRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException(
                        "No pending registration found. Please register again."));

        if (LocalDateTime.now().isAfter(pending.getExpiresAt())) {
            pendingRepository.deleteByEmail(normalizedEmail);
            throw new BusinessException("Verification code has expired. Please register again.");
        }

        if (!pending.getCode().equalsIgnoreCase(code.trim())) {
            throw new BusinessException("Incorrect verification code. Please try again.");
        }

        UserRole role = UserRole.valueOf(pending.getRole());
        boolean needsApproval = ROLES_REQUIRING_APPROVAL.contains(role);

        User user = User.builder()
                .fullName(pending.getFullName())
                .email(pending.getEmail())
                .passwordHash(pending.getPasswordHash())
                .phone(pending.getPhone())
                .role(role)
                .isVerified(true)
                .isActive(!needsApproval)
                .isLocked(false)
                .build();

        User savedUser = userRepository.save(user);
        pendingRepository.deleteByEmail(normalizedEmail);
        logger.info("User created after email verification: {}", normalizedEmail);

        if (needsApproval) {
            sendAdminNotification(savedUser);
            return AuthResponse.builder()
                    .token(null)
                    .refreshToken(null)
                    .type("Bearer")
                    .userId(savedUser.getId())
                    .email(savedUser.getEmail())
                    .fullName(savedUser.getFullName())
                    .role(savedUser.getRole())
                    .isVerified(true)
                    .isActive(false)
                    .pendingApproval(true)
                    .build();
        }

        String accessToken  = jwtUtils.generateToken(savedUser.getEmail());
        String refreshToken = createRefreshToken(savedUser.getEmail());
        return AuthResponse.of(accessToken, refreshToken, savedUser);
    }

    // ── LOGIN ────────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        logger.info("Login attempt: {}", email);

        // Brute force check
        FailedAttempt attempt = failedAttempts.get(email);
        if (attempt != null && attempt.count >= MAX_ATTEMPTS) {
            if (LocalDateTime.now().isBefore(attempt.lockedUntil)) {
                throw new BusinessException(
                        "Too many failed login attempts. Try again in " + LOCKOUT_MINUTES + " minutes.");
            } else {
                failedAttempts.remove(email);
            }
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("No account found with this email."));

        if (!user.getIsActive()) {
            if (ROLES_REQUIRING_APPROVAL.contains(user.getRole())) {
                throw new BusinessException(
                        "Your account is pending admin approval. " +
                                "You will be notified at " + email + " once approved.");
            }
            throw new BusinessException("Your account has been deactivated. Contact support.");
        }

        if (user.getIsLocked()) {
            throw new BusinessException("Your account is locked. Contact support.");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.getPassword()));
        } catch (BadCredentialsException ex) {
            failedAttempts.merge(email,
                    new FailedAttempt(1, LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES)),
                    (old, n) -> new FailedAttempt(old.count + 1, LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES)));
            int remaining = MAX_ATTEMPTS - failedAttempts.get(email).count;
            if (remaining > 0) {
                throw new BusinessException("Invalid password. " + remaining + " attempt(s) remaining.");
            } else {
                throw new BusinessException(
                        "Too many failed login attempts. Try again in " + LOCKOUT_MINUTES + " minutes.");
            }
        }

        failedAttempts.remove(email);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        userRepository.updateLastLogin(user.getId(), LocalDateTime.now()); // native SQL

        logger.info("Login successful: {}", email);
        String accessToken  = jwtUtils.generateToken(user.getEmail());
        String refreshToken = createRefreshToken(user.getEmail());
        return AuthResponse.of(accessToken, refreshToken, user);
    }

    // ── REFRESH TOKEN ────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository.findByToken(rawRefreshToken)
                .orElseThrow(() -> new BusinessException(
                        "Invalid or expired refresh token. Please log in again."));

        if (LocalDateTime.now().isAfter(stored.getExpiresAt())) {
            refreshTokenRepository.delete(stored);
            throw new BusinessException("Refresh token expired. Please log in again.");
        }

        User user = userRepository.findByEmail(stored.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        String newAccessToken  = jwtUtils.generateToken(user.getEmail());
        String newRefreshToken = createRefreshToken(user.getEmail());
        return AuthResponse.of(newAccessToken, newRefreshToken, user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByToken(rawRefreshToken)
                .ifPresent(refreshTokenRepository::delete);
    }

    // ── HELPERS ──────────────────────────────────────────────────────────────

    private String createRefreshToken(String email) {
        refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        refreshTokenRepository.deleteByEmail(email);
        entityManager.flush();

        String token = jwtUtils.generateRefreshToken();
        RefreshToken refreshToken = RefreshToken.builder()
                .token(token)
                .email(email)
                .expiresAt(LocalDateTime.now().plusSeconds(jwtUtils.getRefreshExpiration() / 1000))
                .createdAt(LocalDateTime.now())
                .build();
        refreshTokenRepository.save(refreshToken);
        return token;
    }

    private String generateCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return "***" + email.substring(at);
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private void sendRegistrationEmail(String email, String fullName, String code) {
    if (mailSender == null) {
        logger.warn("Mail not configured — registration code not sent.");
        return;
    }
    try {
        EmailTemplateService templateService = new EmailTemplateService();
        String htmlContent = templateService.generateVerificationEmail(fullName, code, "BENEFICIARY");

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(senderEmail);
        helper.setTo(email);
        helper.setSubject("[Nidaa] Verify Your Email Address 📧");
        helper.setText(htmlContent, true); // true = isHtml

        mailSender.send(message);
    } catch (Exception e) {
        logger.error("Failed to send registration email: {}", e.getMessage());
        throw new BusinessException("Failed to send verification email. Please try again.");
    }
}

    private void sendAdminNotification(User applicant) {
        if (mailSender == null || adminEmail == null || adminEmail.isBlank()) return;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(senderEmail);
            msg.setTo(adminEmail);
            msg.setSubject("[Nidaa] New " + applicant.getRole().name() + " Application — Action Required");
            msg.setText(
                    "A new user has registered and requires approval.\n\n" +
                            "Name:  " + applicant.getFullName() + "\n" +
                            "Email: " + applicant.getEmail() + "\n" +
                            "Phone: " + (applicant.getPhone() != null ? applicant.getPhone() : "Not provided") + "\n" +
                            "Role:  " + applicant.getRole().name() + "\n\n" +
                            "Log in to the admin panel to approve or deny this application.\n\n" +
                            "— Nidaa Platform"
            );
            mailSender.send(msg);
        } catch (Exception e) {
            logger.error("Admin notification failed: {}", e.getMessage());
        }
    }

    // Inner class for brute force tracking
    private static class FailedAttempt {
        final int count;
        final LocalDateTime lockedUntil;
        FailedAttempt(int count, LocalDateTime lockedUntil) {
            this.count = count;
            this.lockedUntil = lockedUntil;
        }
    }
}