package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The priority model documented in README "Request priority". These weights
 * are the single source of truth: the legacy database trigger that used to
 * recompute priority_score with different weights was removed in V15.
 *
 * The result is always within priority_score_range CHECK (0..100). Aging is
 * capped so a long-waiting LOW request rises in the queue without ever
 * outranking a fresh CRITICAL one on waiting time alone.
 */
@Service
public class PriorityScoreService {

    public static final int MAX_SCORE = 100;
    public static final int MAX_AGING_BONUS = 20;

    public int calculate(HelpRequest request) {
        int score = urgencyScore(request.getUrgencyLevel());

        if (Boolean.TRUE.equals(request.getHasChildren())) {
            score += 10;
        }
        if (Boolean.TRUE.equals(request.getHasElderly())) {
            score += 10;
        }
        if (Boolean.TRUE.equals(request.getHasDisabled())) {
            score += 15;
        }

        int peopleCount = request.getPeopleCount() != null ? request.getPeopleCount() : 1;
        score += Math.min(Math.max(peopleCount, 0) * 2, 20);

        score += waitingTimeScore(request.getCreatedAt());

        return clamp(score);
    }

    public int calculate(PsychologicalRequest request) {
        int score = urgencyScore(request.getUrgencyLevel());

        if (Boolean.TRUE.equals(request.getIsCrisis())) {
            score += 35;
        }

        return clamp(score + waitingTimeScore(request.getCreatedAt()));
    }

    private int urgencyScore(String urgencyLevel) {
        return switch (normalize(urgencyLevel)) {
            case "CRITICAL" -> 40;
            case "HIGH" -> 30;
            case "MEDIUM" -> 20;
            case "LOW" -> 10;
            default -> 10;
        };
    }

    /** Half a point per full hour waited, capped at {@link #MAX_AGING_BONUS}. */
    private int waitingTimeScore(LocalDateTime createdAt) {
        if (createdAt == null) {
            return 0;
        }

        long hours = ChronoUnit.HOURS.between(createdAt, LocalDateTime.now());
        return (int) Math.min(Math.max(hours, 0) * 0.5, MAX_AGING_BONUS);
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(score, MAX_SCORE));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase().trim();
    }
}
