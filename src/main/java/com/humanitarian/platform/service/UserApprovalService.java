package com.humanitarian.platform.service;

import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Psychologists are created on duty but not professionally verified; whether
 * admin approval should also set is_verified is an open decision recorded in
 * FUTURE_WORK.md (crisis routing requires it).
 */
@Service
public class UserApprovalService {

    private static final Logger log = LoggerFactory.getLogger(UserApprovalService.class);

    private final UserRepository userRepository;
    private final VolunteerRepository volunteerRepository;
    private final PsychologistRepository psychologistRepository;
    private final OrganizationRepository organizationRepository;

    public UserApprovalService(UserRepository userRepository,
                               VolunteerRepository volunteerRepository,
                               PsychologistRepository psychologistRepository,
                               OrganizationRepository organizationRepository) {
        this.userRepository = userRepository;
        this.volunteerRepository = volunteerRepository;
        this.psychologistRepository = psychologistRepository;
        this.organizationRepository = organizationRepository;
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
                    psychologistRepository.save(Psychologist.builder().user(user).isOnDuty(true).build());
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
        log.info("Approved {} application for user {}", user.getRole(), userId);
        return user;
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
        log.info("Rejected {} application for user {}", user.getRole(), userId);
        return user;
    }
}
