package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.UserProfileDto;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.ProfileRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

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
        boolean newActive = !user.getIsActive();
        userRepository.setActive(userId, newActive); // native SQL — avoids ENUM cast on role field
        user.setIsActive(newActive);
        return user;
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
