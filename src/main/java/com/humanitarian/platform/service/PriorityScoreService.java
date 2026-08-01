package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class PriorityScoreService {

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

        return score;
    }

    public int calculate(PsychologicalRequest request) {
        int score = urgencyScore(request.getUrgencyLevel());

        if (Boolean.TRUE.equals(request.getIsCrisis())) {
            score += 35;
        }

        return score + waitingTimeScore(request.getCreatedAt());
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

    private int waitingTimeScore(LocalDateTime createdAt) {
        if (createdAt == null) {
            return 0;
        }

        long hours = ChronoUnit.HOURS.between(createdAt, LocalDateTime.now());
        return (int) (Math.max(hours, 0) * 0.5);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase().trim();
    }
}
