package com.humanitarian.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrisisDetectorServiceTest {

    private final CrisisDetectorService service = new CrisisDetectorService();

    @Test
    void crisisCategoryAlwaysDetected() {
        assertTrue(service.detect("CRISIS_SUPPORT", "I need help"));
    }

    @Test
    void crisisCategoryWithSpacesDetected() {
        assertTrue(service.detect("Crisis Support", "I need help"));
    }

    @Test
    void crisisKeywordInDescriptionDetected() {
        assertTrue(service.detect("INDIVIDUAL", "I feel suicide risk"));
    }

    @Test
    void normalCaseNotDetected() {
        assertFalse(service.detect("INDIVIDUAL", "I feel anxious sometimes"));
    }

    @Test
    void arabicCrisisPhraseDetected() {
        assertTrue(service.detect("INDIVIDUAL", "لا أريد أن أعيش"));
    }

    @Test
    void russianCrisisPhraseDetected() {
        assertTrue(service.detect("INDIVIDUAL", "не хочу жить"));
    }
}
