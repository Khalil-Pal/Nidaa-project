package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.UserProfileDto;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.ProfileRepository;
import com.humanitarian.platform.repository.RefreshTokenRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AdminAuditService adminAudit;

    /**
     * Soft-deletes an account (D-2): personal data is replaced in place and the
     * row stays so requests, assignments and messages keep their history. The
     * profile's address, coordinates, bio and avatar go too, since a home
     * address identifies a person as surely as a name. Every session ends.
     */
    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        anonymise(user);
        adminAudit.record("USER_DELETED", "USER", userId, Map.of("role", user.getRole().name()));
    }

    /** Anonymise-in-place (D-2): the row stays for the aid history, the person is gone from it. */
    private void anonymise(User user) {
        Long userId = user.getId();
        if (user.getDeletedAt() != null) {
            throw new BusinessException("This account has already been deleted.");
        }
        String email = user.getEmail();
        if (userRepository.softDelete(userId, java.time.LocalDateTime.now()) == 0) {
            throw new BusinessException("This account has already been deleted.");
        }
        profileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.setAddress(null);
            profile.setLatitude(null);
            profile.setLongitude(null);
            profile.setBio(null);
            profile.setAvatarUrl(null);
            profileRepository.save(profile);
        });
        refreshTokenRepository.deleteByEmail(email);
    }

    /** Self-deletion requires the caller to prove they hold the password. */
    @Transactional
    public void deleteOwnAccount(String password) {
        User me = getCurrentUser();
        if (password == null || password.isBlank()
                || !passwordEncoder.matches(password, me.getPasswordHash())) {
            throw new BusinessException("Enter your current password to delete the account.");
        }
        anonymise(me);
        log.info("User {} deleted their own account", me.getId());
    }

    /**
     * Creates a BENEFICIARY account for a person a provider is filing a help
     * request for (ON-1). Mirrors the account created by
     * AuthService.verifyRegistration but skips the email step: the person has
     * not proven control of the address, so is_verified stays false. The
     * password is random and never disclosed; the person claims the account
     * later through the password-reset flow, which requires is_active.
     */
    @Transactional
    public User createUnverifiedBeneficiary(String fullName, String email, String phone) {
        String normalizedEmail = email.toLowerCase().trim();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException("An account already exists for " + normalizedEmail);
        }
        User user = User.builder()
                .fullName(fullName.trim())
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .phone(phone == null || phone.isBlank() ? null : phone.trim())
                .role(UserRole.BENEFICIARY)
                .isVerified(false)
                .isActive(true)
                .isLocked(false)
                .build();
        return userRepository.save(user);
    }

    // Get currently logged in user
    public User getCurrentUser() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    // Get user by ID
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }

    // Get all users (admin only)
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    // Get users by role
    public List<User> getUsersByRole(UserRole role) {
        return userRepository.findByRole(role);
    }

    // Update user profile
    @Transactional
    public Profile updateProfile(Long userId, UserProfileDto dto) {
        validateCoordinates(dto);
        User user = getUserById(userId);

        // Update user full name and phone
        if (dto.getFullName() != null && !dto.getFullName().isBlank()) {
            user.setFullName(dto.getFullName().trim());
        }
        if (dto.getPhone() != null) user.setPhone(dto.getPhone().trim());
        userRepository.save(user);

        // Update or create profile
        Profile profile = profileRepository.findByUserId(userId)
                .orElse(Profile.builder().user(user).build());

        if (dto.getBio() != null) profile.setBio(dto.getBio());
        if (dto.getAddress() != null) profile.setAddress(dto.getAddress());
        if (dto.getPreferredLanguage() != null) profile.setPreferredLanguage(dto.getPreferredLanguage());
        if (dto.getLatitude() != null) profile.setLatitude(dto.getLatitude());
        if (dto.getLongitude() != null) profile.setLongitude(dto.getLongitude());

        return profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public UserProfileDto getCurrentProfile() {
        User user = getCurrentUser();
        UserProfileDto response = new UserProfileDto();
        response.setFullName(user.getFullName());
        response.setPhone(user.getPhone());
        profileRepository.findByUserId(user.getId()).ifPresent(profile -> {
            response.setBio(profile.getBio());
            response.setAddress(profile.getAddress());
            response.setPreferredLanguage(profile.getPreferredLanguage());
            response.setLatitude(profile.getLatitude());
            response.setLongitude(profile.getLongitude());
        });
        return response;
    }

    // Block or unblock user (admin only)
    @Transactional
    public User toggleUserActive(Long userId) {
        User user = getUserById(userId);
        user.setIsActive(!user.getIsActive());
        User saved = userRepository.save(user);
        adminAudit.record(Boolean.TRUE.equals(saved.getIsActive()) ? "USER_ACTIVATED" : "USER_DEACTIVATED",
                "USER", userId, Map.of("role", saved.getRole().name()));
        return saved;
    }

    // Search users by name
    public List<User> searchUsers(String name) {
        return userRepository.searchByName(name);
    }

    private void validateCoordinates(UserProfileDto dto) {
        boolean hasLatitude = dto.getLatitude() != null;
        boolean hasLongitude = dto.getLongitude() != null;
        if (hasLatitude != hasLongitude) {
            throw new BusinessException(
                    "Latitude and longitude must be provided together.");
        }
    }
}
