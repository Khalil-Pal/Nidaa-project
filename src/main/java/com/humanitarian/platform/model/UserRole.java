package com.humanitarian.platform.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * User roles in the system.
 * Accepts any case from JSON: "volunteer", "VOLUNTEER", "Volunteer" all work.
 */
public enum UserRole {

    BENEFICIARY,
    VOLUNTEER,
    PSYCHOLOGIST,
    ORGANIZATION,
    ADMIN;

    /**
     * @JsonCreator tells Jackson to use this method when deserializing.
     * It converts any case input to uppercase before matching.
     */
    @JsonCreator
    public static UserRole fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Role cannot be null or empty. Accepted values: " +
                            java.util.Arrays.toString(UserRole.values())
            );
        }
        try {
            return UserRole.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid role: '" + value + "'. Accepted values: " +
                            java.util.Arrays.toString(UserRole.values())
            );
        }
    }

    /**
     * @JsonValue controls how the enum is serialized back to JSON.
     * Returns lowercase so the frontend always receives: "volunteer"
     */
    @JsonValue
    public String toJsonValue() {
        return this.name().toLowerCase();
    }
}