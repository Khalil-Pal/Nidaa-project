package com.humanitarian.platform.dto;

import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String token;
    private String type;
    private Long userId;
    private String email;
    private String fullName;
    private UserRole role;
    private Boolean isVerified;
    private Boolean isActive;
    private Boolean pendingApproval; // true if waiting for admin to approve

    public static AuthResponse of(String token, User user) {
        boolean needsApproval = !user.getIsActive() &&
                (user.getRole() == UserRole.VOLUNTEER ||
                        user.getRole() == UserRole.PSYCHOLOGIST ||
                        user.getRole() == UserRole.ORGANIZATION);

        return AuthResponse.builder()
                .token(token)
                .type("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .isVerified(user.getIsVerified())
                .isActive(user.getIsActive())
                .pendingApproval(needsApproval)
                .build();
    }
}