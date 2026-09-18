package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
 *
 * Aging reads "now" from a {@link Clock}. The application uses the system
 * clock (the constructor Spring picks); the matching study (EV-1) builds its
 * own instance on a simulated clock so the same model ages requests in
 * simulated time.
 *
 * The weights themselves are a {@link PriorityWeights} record, defaulting to the
 * production model and defended in {@code docs/SCORING.md}. Only EV-1's
 * sensitivity analysis constructs anything else.
 */
@Service
public class PriorityScoreService {

    public static final int MAX_SCORE = 100;
    public static final int MAX_AGING_BONUS = 20;

    private final Clock clock;
    private final PriorityWeights weights;

    public PriorityScoreService() {
        this(Clock.systemDefaultZone(), PriorityWeights.DEFAULT);
    }

    public PriorityScoreService(Clock clock) {
        this(clock, PriorityWeights.DEFAULT);
    }

    public PriorityScoreService(Clock clock, PriorityWeights weights) {
        this.clock = clock;
        this.weights = weights;
    }

    public int calculate(HelpRequest request) {
        int score = urgencyScore(request.getUrgencyLevel());

        if (Boolean.TRUE.equals(request.getHasChildren())) {
            score += weights.children();
        }
        if (Boolean.TRUE.equals(request.getHasElderly())) {
            score += weights.elderly();
        }
        if (Boolean.TRUE.equals(request.getHasDisabled())) {
            score += weights.disabled();
        }

        int peopleCount = request.getPeopleCount() != null ? request.getPeopleCount() : 1;
        score += Math.min(Math.max(peopleCount, 0) * weights.perPerson(), weights.peopleCap());

        score += waitingTimeScore(request.getCreatedAt());

        return clamp(score);
    }

    public int calculate(PsychologicalRequest request) {
        int score = urgencyScore(request.getUrgencyLevel());

        if (Boolean.TRUE.equals(request.getIsCrisis())) {
            score += weights.crisisBonus();
        }

        return clamp(score + waitingTimeScore(request.getCreatedAt()));
    }

    private int urgencyScore(String urgencyLevel) {
        return switch (normalize(urgencyLevel)) {
            case "CRITICAL" -> weights.critical();
            case "HIGH" -> weights.high();
            case "MEDIUM" -> weights.medium();
            case "LOW" -> weights.low();
            default -> weights.low();
        };
    }

    /** Half a point per full hour waited, capped at {@link #MAX_AGING_BONUS} by the default weights. */
    private int waitingTimeScore(LocalDateTime createdAt) {
        if (createdAt == null) {
            return 0;
        }

        long hours = ChronoUnit.HOURS.between(createdAt, LocalDateTime.now(clock));
        return (int) Math.min(Math.max(hours, 0) * weights.agingPerHour(), weights.agingCap());
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(score, MAX_SCORE));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase().trim();
    }
}
