package com.humanitarian.platform.service;

import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.dto.PsychologistVerificationResponse;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin approval and rejection of provider applications (A-2).
 *
 * Approval activates the account and creates the role's profile row in one
 * transaction, so a failure in either step leaves the application pending
 * rather than half-approved. Before this service the same work spanned a
 * repository call and three JdbcTemplate inserts inside a controller.
 *
 * Re-approving an already approved account is a no-op for the profile row.
 *
 * Approval and professional verification are two separate administrator
 * actions (decision after Gate 4). Approval says the person may use the
 * platform; {@link #setPsychologistVerification} says an administrator has
 * checked the psychologist's credentials. Crisis routing needs both flags plus
 * the psychologist's own duty toggle (UX-2), so a newly approved psychologist
 * is off duty and unverified until they go on duty and are verified.
 */
@Service
public class UserApprovalService {

    private final UserRepository userRepository;
    private final VolunteerRepository volunteerRepository;
    private final PsychologistRepository psychologistRepository;
    private final OrganizationRepository organizationRepository;
    private final AdminAuditService adminAudit;
    private final NotificationService notifications;

    public UserApprovalService(UserRepository userRepository,
                               VolunteerRepository volunteerRepository,
                               PsychologistRepository psychologistRepository,
                               OrganizationRepository organizationRepository,
                               AdminAuditService adminAudit,
                               NotificationService notifications) {
        this.userRepository = userRepository;
        this.volunteerRepository = volunteerRepository;
        this.psychologistRepository = psychologistRepository;
        this.organizationRepository = organizationRepository;
        this.adminAudit = adminAudit;
        this.notifications = notifications;
    }

    @Transactional
    public User approve(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getDeletedAt() != null) {
            throw new BusinessException("This account has been deleted.");
        }
        user.setIsActive(true);
        user.setIsVerified(true);
        userRepository.save(user);

        switch (user.getRole()) {
            case VOLUNTEER -> {
                if (volunteerRepository.findByUserId(userId).isEmpty()) {
                    volunteerRepository.save(Volunteer.builder().user(user).isAvailable(true).build());
                }
            }
            case PSYCHOLOGIST -> {
                if (psychologistRepository.findByUserId(userId).isEmpty()) {
                    // off duty and unverified: routing starts only after both change (class comment)
                    psychologistRepository.save(Psychologist.builder().user(user).isOnDuty(false).build());
                }
            }
            case ORGANIZATION -> {
                if (organizationRepository.findByUserId(userId).isEmpty()) {
                    organizationRepository.save(Organization.builder()
                            .user(user).officialName(user.getFullName()).isAvailable(true).build());
                }
            }
            default -> { /* beneficiaries and admins have no profile row */ }
        }
        adminAudit.record("USER_APPROVED", "USER", userId, Map.of("role", user.getRole().name()));
        // N-1: waiting for them at first sign-in. A rejected application is deleted
        // outright (reject), so it can only be told by e-mail, which the controller sends.
        notifications.notify(userId, "Your application was approved",
                "Welcome to Nidaa. Your account as a " + user.getRole().name().toLowerCase(java.util.Locale.ROOT)
                        + " is active.", NotificationService.REF_USER, userId);
        return user;
    }

    /**
     * Records that an administrator has verified (or no longer vouches for) a
     * psychologist's professional credentials. Sets {@code is_verified},
     * {@code verified_at} and {@code verified_by}; does not touch the duty flag,
     * which stays the psychologist's own. Idempotent: repeating the same state
     * changes nothing and writes no audit row.
     */
    @Transactional
    public PsychologistVerificationResponse setPsychologistVerification(Long userId, boolean verified) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getDeletedAt() != null) {
            throw new BusinessException("This account has been deleted.");
        }
        if (user.getRole() != UserRole.PSYCHOLOGIST) {
            throw new BusinessException("Only psychologists have professional credentials to verify.");
        }
        Psychologist profile = psychologistRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("Approve the application first; the psychologist profile does not exist yet."));

        if (Boolean.TRUE.equals(profile.getIsVerified()) != verified) {
            profile.setIsVerified(verified);
            profile.setVerifiedAt(verified ? LocalDateTime.now() : null);
            profile.setVerifiedBy(verified ? adminAudit.currentActorId() : null);
            psychologistRepository.save(profile);
            adminAudit.record(verified ? "PSYCHOLOGIST_VERIFIED" : "PSYCHOLOGIST_VERIFICATION_REVOKED",
                    "USER", userId, Map.of("psychologistId", profile.getId()));
        }
        return new PsychologistVerificationResponse(userId, profile.getId(),
                Boolean.TRUE.equals(profile.getIsVerified()), Boolean.TRUE.equals(profile.getIsOnDuty()),
                profile.getVerifiedAt());
    }

    /**
     * Rejects a pending application. The account has never been active, so it
     * has no aid history to preserve and is removed outright.
     */
    @Transactional
    public User reject(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException("This account is already active; deactivate or delete it instead.");
        }
        userRepository.delete(user);
        adminAudit.record("USER_REJECTED", "USER", userId, Map.of("role", user.getRole().name()));
        return user;
    }
}
