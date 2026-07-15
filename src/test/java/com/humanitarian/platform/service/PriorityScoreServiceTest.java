package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PriorityScoreServiceTest {

    private final PriorityScoreService service = new PriorityScoreService();

    @Test
    void criticalWithVulnerabilitiesScoresHighest() {
        HelpRequest request = HelpRequest.builder()
                .urgencyLevel("CRITICAL")
                .peopleCount(5)
                .hasChildren(true)
                .hasElderly(true)
                .hasDisabled(true)
                .createdAt(LocalDateTime.now())
                .build();

        int score = service.calculate(request);

        assertTrue(score >= 85, "Expected score >= 85, got " + score);
    }

    @Test
    void lowUrgencyNoFlagsScoresLowest() {
        HelpRequest request = HelpRequest.builder()
                .urgencyLevel("LOW")
                .peopleCount(1)
                .hasChildren(false)
                .hasElderly(false)
                .hasDisabled(false)
                .createdAt(LocalDateTime.now())
                .build();

        int score = service.calculate(request);

        assertTrue(score <= 15, "Expected score <= 15, got " + score);
    }

    @Test
    void waitingTimeIncreasesScore() {
        HelpRequest oldRequest = HelpRequest.builder()
                .urgencyLevel("MEDIUM")
                .peopleCount(1)
                .hasChildren(false)
                .hasElderly(false)
                .hasDisabled(false)
                .createdAt(LocalDateTime.now().minusHours(48))
                .build();

        HelpRequest freshRequest = HelpRequest.builder()
                .urgencyLevel("MEDIUM")
                .peopleCount(1)
                .hasChildren(false)
                .hasElderly(false)
                .hasDisabled(false)
                .createdAt(LocalDateTime.now())
                .build();

        assertTrue(service.calculate(oldRequest) > service.calculate(freshRequest));
    }
}
