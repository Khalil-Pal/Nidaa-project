package com.humanitarian.platform.util;

import java.util.Map;
import java.util.Set;

/**
 * The status machine shared by help and psychological requests (both use the
 * {@code help_request_status} enum): PENDING -> ASSIGNED or CANCELLED,
 * ASSIGNED -> IN_PROGRESS, COMPLETED or CANCELLED, IN_PROGRESS -> COMPLETED or
 * CANCELLED, and the two closed states are final. ASSIGNED -> COMPLETED stays,
 * so a short delivery need not step through IN_PROGRESS (W-1).
 * One definition instead of a copy per service (C-3). Which of these a given
 * caller may perform is each service's per-role decision, made before the
 * transition is validated.
 */
public final class RequestTransitions {

    private static final Map<String, Set<String>> VALID = Map.of(
            "PENDING",     Set.of("ASSIGNED", "CANCELLED"),
            "ASSIGNED",    Set.of("IN_PROGRESS", "COMPLETED", "CANCELLED"),
            "IN_PROGRESS", Set.of("COMPLETED", "CANCELLED"),
            "COMPLETED",   Set.of(),
            "CANCELLED",   Set.of()
    );

    private RequestTransitions() {
    }

    public static boolean allows(String from, String to) {
        return VALID.getOrDefault(from, Set.of()).contains(to);
    }
}
