package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.UserProfileDto;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.ProfileRepository;
import com.humanitarian.platform.repository.UserRepository;
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
    public Profile updateProfile(Long userId, UserProfileDto dto) {
        User user = getUserById(userId);

        // Update user full name and phone
        if (dto.getFullName() != null) user.setFullName(dto.getFullName());
        if (dto.getPhone() != null) user.setPhone(dto.getPhone());
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

    // Block or unblock user (admin only)
    public User toggleUserActive(Long userId) {
        User user = getUserById(userId);
        user.setIsActive(!user.getIsActive());
        return userRepository.save(user);
    }

    // Search users by name
    public List<User> searchUsers(String name) {
        return userRepository.searchByName(name);
    }
}