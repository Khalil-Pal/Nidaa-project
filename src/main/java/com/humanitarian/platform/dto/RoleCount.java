package com.humanitarian.platform.dto;

import com.humanitarian.platform.model.UserRole;

/** Users per role, one row of a GROUP BY count (Q-2). */
public record RoleCount(UserRole role, Long count) {
}
