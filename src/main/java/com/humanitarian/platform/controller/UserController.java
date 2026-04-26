package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.UserProfileDto;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")

public class UserController {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // GET /api/users/me
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<User>> getCurrentUser() {
        return ResponseEntity.ok(ApiResponse.success("User retrieved", userService.getCurrentUser()));
    }

    // PUT /api/users/me/profile — update name, phone, bio
    @PutMapping("/me/profile")
    public ResponseEntity<ApiResponse<?>> updateProfile(@RequestBody UserProfileDto dto) {
        User current = userService.getCurrentUser();

        // Update fullName and phone directly on User
        if (dto.getFullName() != null && !dto.getFullName().isBlank()) {
            current.setFullName(dto.getFullName().trim());
        }
        if (dto.getPhone() != null) {
            current.setPhone(dto.getPhone().trim());
        }
        userRepository.save(current);

        // Also update profile table
        var profile = userService.updateProfile(current.getId(), dto);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", profile));
    }

    // Also support old endpoint path
    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<?>> updateProfileAlt(@RequestBody UserProfileDto dto) {
        return updateProfile(dto);
    }

    // POST /api/users/change-password
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> body) {
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            User user = userService.getCurrentUser();
            String current = body.get("currentPassword");
            String newPw   = body.get("newPassword");

            if (current == null || newPw == null || newPw.length() < 6) {
                res.put("success", false);
                res.put("message", "Password must be at least 6 characters.");
                return ResponseEntity.badRequest().body(res);
            }

            if (!passwordEncoder.matches(current, user.getPasswordHash())) {
                res.put("success", false);
                res.put("message", "Current password is incorrect.");
                return ResponseEntity.badRequest().body(res);
            }

            user.setPasswordHash(passwordEncoder.encode(newPw));
            userRepository.save(user);
            res.put("success", true);
            res.put("message", "Password changed successfully.");
        } catch (Exception e) {
            res.put("success", false);
            res.put("message", e.getMessage());
        }
        return ResponseEntity.ok(res);
    }

    // DELETE /api/users/me — delete own account
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<?>> deleteMyAccount() {
        User user = userService.getCurrentUser();
        userRepository.delete(user);
        return ResponseEntity.ok(ApiResponse.success("Account deleted", null));
    }

    // GET /api/users/{id}
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<User>> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("User retrieved", userService.getUserById(id)));
    }

    // GET /api/users — admin only
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<User>>> getAllUsers() {
        return ResponseEntity.ok(ApiResponse.success("Users retrieved", userService.getAllUsers()));
    }

    // GET /api/users/role/{role}
    @GetMapping("/role/{role}")
    public ResponseEntity<ApiResponse<List<User>>> getUsersByRole(@PathVariable String role) {
        List<User> users = userService.getUsersByRole(UserRole.valueOf(role.toUpperCase()));
        return ResponseEntity.ok(ApiResponse.success("Users retrieved", users));
    }

    // GET /api/users/search
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<User>>> searchUsers(@RequestParam String name) {
        return ResponseEntity.ok(ApiResponse.success("Search results", userService.searchUsers(name)));
    }

    // PUT /api/users/{id}/toggle-active — admin only
    @PutMapping("/{id}/toggle-active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<User>> toggleActive(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Status updated", userService.toggleUserActive(id)));
    }
}