package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void crisisPsychologicalRequestGetsUrgencyAndCrisisPriority() {
        PsychologicalRequest request = PsychologicalRequest.builder()
                .urgencyLevel("CRITICAL")
                .isCrisis(true)
                .build();

        assertEquals(75, service.calculate(request));
    }

    @Test
    void crisisPsychologicalRequestOutranksEquivalentNonCrisisRequest() {
        PsychologicalRequest crisis = PsychologicalRequest.builder()
                .urgencyLevel("HIGH")
                .isCrisis(true)
                .createdAt(LocalDateTime.now())
                .build();
        PsychologicalRequest nonCrisis = PsychologicalRequest.builder()
                .urgencyLevel("HIGH")
                .isCrisis(false)
                .createdAt(LocalDateTime.now())
                .build();

        assertTrue(service.calculate(crisis) > service.calculate(nonCrisis));
    }

    @Test
    void psychologicalWaitingTimeIncreasesScore() {
        PsychologicalRequest oldRequest = PsychologicalRequest.builder()
                .urgencyLevel("MEDIUM")
                .isCrisis(false)
                .createdAt(LocalDateTime.now().minusHours(48))
                .build();
        PsychologicalRequest freshRequest = PsychologicalRequest.builder()
                .urgencyLevel("MEDIUM")
                .isCrisis(false)
                .createdAt(LocalDateTime.now())
                .build();

        assertTrue(service.calculate(oldRequest) > service.calculate(freshRequest));
    }

    // -- D-5: caps ------------------------------------------------------------

    @Test
    void agingBonusIsCappedSoAnOldLowRequestCannotOutrankAFreshCriticalOne() {
        HelpRequest ancientLow = HelpRequest.builder()
                .urgencyLevel("LOW").peopleCount(1)
                .createdAt(LocalDateTime.now().minusHours(400))   // 200 uncapped points
                .build();
        HelpRequest freshCritical = HelpRequest.builder()
                .urgencyLevel("CRITICAL").peopleCount(1)
                .createdAt(LocalDateTime.now())
                .build();

        assertEquals(10 + 2 + PriorityScoreService.MAX_AGING_BONUS, service.calculate(ancientLow));
        assertTrue(service.calculate(freshCritical) > service.calculate(ancientLow));
    }

    @Test
    void totalNeverExceedsTheDatabaseCheckConstraint() {
        HelpRequest worstCase = HelpRequest.builder()
                .urgencyLevel("CRITICAL").peopleCount(50)
                .hasChildren(true).hasElderly(true).hasDisabled(true)
                .createdAt(LocalDateTime.now().minusHours(400))
                .build();
        PsychologicalRequest worstPsych = PsychologicalRequest.builder()
                .urgencyLevel("CRITICAL").isCrisis(true)
                .createdAt(LocalDateTime.now().minusHours(400))
                .build();

        assertEquals(PriorityScoreService.MAX_SCORE, service.calculate(worstCase));   // 115 before clamping
        assertTrue(service.calculate(worstPsych) <= PriorityScoreService.MAX_SCORE);
    }
}
