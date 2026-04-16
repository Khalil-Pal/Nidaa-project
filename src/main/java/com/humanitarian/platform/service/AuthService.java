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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    // Roles that require admin approval
    private static final Set<UserRole> ROLES_REQUIRING_APPROVAL = Set.of(
            UserRole.VOLUNTEER,
            UserRole.PSYCHOLOGIST,
            UserRole.ORGANIZATION
    );

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String senderEmail;

    // Admin receives all notifications at this address
    @Value("${nidaa.admin.email:supp0rtnidaa@yandex.ru}")
    private String adminEmail;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        logger.info("Registering user: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail().toLowerCase().trim())) {
            throw new RuntimeException("This email is already registered.");
        }

        // Roles that need admin approval are created as inactive
        boolean needsApproval = ROLES_REQUIRING_APPROVAL.contains(request.getRole());

        User user = User.builder()
                .fullName(request.getFullName().trim())
                .email(request.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .phone(request.getPhone())
                .role(request.getRole())
                .isVerified(false)
                .isActive(!needsApproval) // inactive until admin approves
                .isLocked(false)
                .build();

        User savedUser = userRepository.save(user);
        logger.info("User saved with ID: {} | role: {} | active: {}",
                savedUser.getId(), savedUser.getRole(), savedUser.getIsActive());

        // Send admin notification email for roles requiring approval
        if (needsApproval) {
            sendAdminNotification(savedUser);
        }

        String token = jwtUtils.generateToken(savedUser.getEmail());
        return AuthResponse.of(token, savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        logger.info("Login attempt: {}", email);

        // Check if user exists first
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("No account found with this email."));

        // Check if account is pending approval
        if (!user.getIsActive() && ROLES_REQUIRING_APPROVAL.contains(user.getRole())) {
            throw new RuntimeException(
                    "Your account is pending admin approval. " +
                            "You will be notified by email once approved."
            );
        }

        if (!user.getIsActive()) {
            throw new RuntimeException("Your account has been deactivated. Please contact support.");
        }

        if (user.getIsLocked()) {
            throw new RuntimeException("Your account is locked. Please contact support.");
        }

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        logger.info("Login successful: {}", email);

        String token = jwtUtils.generateToken(user.getEmail());
        return AuthResponse.of(token, user);
    }

    private void sendAdminNotification(User applicant) {
        if (mailSender == null || adminEmail == null || adminEmail.isBlank()) {
            logger.warn("Mail not configured — skipping admin notification for {}", applicant.getEmail());
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(adminEmail);
            message.setSubject("[Nidaa] New " + applicant.getRole().name() + " Application — Action Required");
            message.setText(
                    "A new user has registered on Nidaa and requires your approval.\n\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                            "APPLICANT DETAILS\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                            "Name:   " + applicant.getFullName() + "\n" +
                            "Email:  " + applicant.getEmail() + "\n" +
                            "Phone:  " + (applicant.getPhone() != null ? applicant.getPhone() : "Not provided") + "\n" +
                            "Role:   " + applicant.getRole().name() + "\n" +
                            "Date:   " + LocalDateTime.now() + "\n\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                            "ACTION REQUIRED\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                            "This account is currently INACTIVE and waiting for approval.\n\n" +
                            "To approve this applicant, please contact them directly at:\n" +
                            applicant.getEmail() + "\n\n" +
                            "Once you verify their credentials, activate their account in the admin panel.\n\n" +
                            "— Nidaa Platform"
            );
            mailSender.send(message);
            logger.info("Admin notification sent for applicant: {}", applicant.getEmail());
        } catch (Exception e) {
            logger.error("Failed to send admin notification: {}", e.getMessage());
            // Don't throw — registration should still succeed even if email fails
        }
    }
}