package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.AuthResponse;
import com.humanitarian.platform.dto.LoginRequest;
import com.humanitarian.platform.dto.RegisterRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.security.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    // Brute force protection — in-memory per-email attempt counter
    private final Map<String, FailedAttempt> failedAttempts = new ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS    = 5;
    private static final int LOCKOUT_MINUTES = 15;

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private AuthenticationManager authenticationManager;
    @Autowired(required = false) private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String senderEmail;

    @Value("${nidaa.admin.email:supp0rtnidaa@yandex.ru}")
    private String adminEmail;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        logger.info("Registering user: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail().toLowerCase().trim())) {
            throw new BusinessException("This email is already registered.");
        }

        boolean needsApproval = ROLES_REQUIRING_APPROVAL.contains(request.getRole());

        User user = User.builder()
                .fullName(request.getFullName().trim())
                .email(request.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .phone(request.getPhone())
                .role(request.getRole())
                .isVerified(false)
                .isActive(!needsApproval)
                .isLocked(false)
                .build();

        User savedUser = userRepository.save(user);

        if (needsApproval) {
            sendAdminNotification(savedUser);
            // Return response WITHOUT a token — pending users cannot log in yet
            return AuthResponse.builder()
                    .token(null)          // NO token for pending accounts
                    .type("Bearer")
                    .userId(savedUser.getId())
                    .email(savedUser.getEmail())
                    .fullName(savedUser.getFullName())
                    .role(savedUser.getRole())
                    .isVerified(false)
                    .isActive(false)
                    .pendingApproval(true)
                    .build();
        }

        String token = jwtUtils.generateToken(savedUser.getEmail());
        return AuthResponse.of(token, savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        logger.info("Login attempt: {}", email);

        // Brute force check — before any DB or auth work
        FailedAttempt attempt = failedAttempts.get(email);
        if (attempt != null && attempt.count >= MAX_ATTEMPTS) {
            if (LocalDateTime.now().isBefore(attempt.lockedUntil)) {
                throw new BusinessException("Too many failed login attempts. Try again in " + LOCKOUT_MINUTES + " minutes.");
            } else {
                failedAttempts.remove(email); // lockout expired, reset
            }
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("No account found with this email."));

        // Block ALL inactive accounts — regardless of role
        if (!user.getIsActive()) {
            if (ROLES_REQUIRING_APPROVAL.contains(user.getRole())) {
                throw new BusinessException(
                        "Your account is pending admin approval. " +
                                "You will be notified at " + email + " once approved."
                );
            }
            throw new BusinessException("Your account has been deactivated. Contact support.");
        }

        if (user.getIsLocked()) {
            throw new BusinessException("Your account is locked. Contact support.");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.getPassword())
            );
        } catch (org.springframework.security.authentication.BadCredentialsException ex) {
            // Record failed attempt
            failedAttempts.merge(email,
                new FailedAttempt(1, LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES)),
                (old, n) -> new FailedAttempt(old.count + 1, LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES)));
            int remaining = MAX_ATTEMPTS - failedAttempts.get(email).count;
            if (remaining > 0) {
                throw new BusinessException("Invalid password. " + remaining + " attempt(s) remaining.");
            } else {
                throw new BusinessException("Too many failed login attempts. Try again in " + LOCKOUT_MINUTES + " minutes.");
            }
        }

        // Success — clear any previous failed attempts
        failedAttempts.remove(email);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        logger.info("Login successful: {}", email);
        String token = jwtUtils.generateToken(user.getEmail());
        return AuthResponse.of(token, user);
    }

    // Inner class to track failed login attempts
    private static class FailedAttempt {
        final int count;
        final LocalDateTime lockedUntil;
        FailedAttempt(int count, LocalDateTime lockedUntil) {
            this.count = count;
            this.lockedUntil = lockedUntil;
        }
    }

    private void sendAdminNotification(User applicant) {
        if (mailSender == null || adminEmail == null || adminEmail.isBlank()) return;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
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
}