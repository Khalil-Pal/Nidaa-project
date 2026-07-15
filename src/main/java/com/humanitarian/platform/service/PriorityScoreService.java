package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class PriorityScoreService {

    public int calculate(HelpRequest request) {
        int score = 0;

        score += switch (normalize(request.getUrgencyLevel())) {
            case "CRITICAL" -> 40;
            case "HIGH" -> 30;
            case "MEDIUM" -> 20;
            case "LOW" -> 10;
            default -> 10;
        };

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

        LocalDateTime createdAt = request.getCreatedAt();
        if (createdAt != null) {
            long hours = ChronoUnit.HOURS.between(createdAt, LocalDateTime.now());
            score += (int) (Math.max(hours, 0) * 0.5);
        }

        return score;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase().trim();
    }
}
