package com.humanitarian.platform.util;

import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import java.util.Set;

/**
 * The rules the community feed and everything attached to it share (CM-1): who
 * may take part, how long a text may be, what a moderator must give. One
 * definition for posts and comments instead of a copy per service (C-3).
 */
public final class CommunityRules {

    public static final Set<UserRole> COMMUNITY_ROLES = Set.of(
            UserRole.VOLUNTEER,
            UserRole.PSYCHOLOGIST,
            UserRole.ORGANIZATION,
            UserRole.ADMIN
    );

    public static final int MAX_CONTENT_LENGTH = 1000;

    private CommunityRules() {
    }

    /** The caller, if their role admits them to the community feed; 403 otherwise. */
    public static User requireCommunityRole(User currentUser) {
        if (currentUser.getRole() == null || !COMMUNITY_ROLES.contains(currentUser.getRole())) {
            throw new UnauthorizedException(
                    "Only volunteers, psychologists, organizations, and administrators " +
                            "can access the community feed.");
        }
        return currentUser;
    }

    public static User requireAdmin(User currentUser) {
        if (currentUser.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedException("Only administrators can moderate community messages.");
        }
        return currentUser;
    }

    /** Trimmed, non-blank, at most 1000 characters; {@code what} names the field in the message. */
    public static String requireContent(String content, String what) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(what + " content is required.");
        }
        String normalized = content.trim();
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(what + " content must not exceed " + MAX_CONTENT_LENGTH + " characters.");
        }
        return normalized;
    }

    public static String requireDeletionReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("A deletion reason is required.");
        }
        return reason.trim();
    }

    public static void requirePage(int page, int size) {
        if (page < 0) {
            throw new BusinessException("Page number must be zero or greater.");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("Page size must be between 1 and 100.");
        }
    }
}
