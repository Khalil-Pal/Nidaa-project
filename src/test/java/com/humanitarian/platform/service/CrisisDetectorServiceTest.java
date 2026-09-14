package com.humanitarian.platform.service;

import com.humanitarian.platform.service.CrisisDetectorService.Assessment;
import com.humanitarian.platform.service.CrisisDetectorService.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L-2: weighted two-tier scoring on word boundaries, no generic terms. */
class CrisisDetectorServiceTest {

    private final CrisisDetectorService service = new CrisisDetectorService();

    // -- explicit crisis category --------------------------------------------

    @Test
    void crisisCategoryAlwaysDetected() {
        assertTrue(service.detect("CRISIS_SUPPORT", "I need help"));
        assertTrue(service.assess("CRISIS", "I need help").isCrisis());
    }

    @Test
    void crisisCategoryWithSpacesDetected() {
        assertTrue(service.detect("Crisis Support", "I need help"));
    }

    // -- HIGH tier: one term is enough ----------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "I feel suicide risk",
            "Sometimes I think about how to kill myself.",
            "I want to end my life",
            "I have started to self-harm again",   // hyphenated form
            "لا أريد أن أعيش",
            "أفكر في انتحار",
            "не хочу жить больше",
            "Постоянные мысли про суицид"
    })
    void highTierTermsAreCrisis(String description) {
        Assessment a = service.assess("INDIVIDUAL", description);
        assertEquals(Level.CRISIS, a.level(), description);
        assertTrue(a.score() >= CrisisDetectorService.CRISIS_THRESHOLD);
    }

    @Test
    void inflectedFormsAreNotMatchedBecauseBoundariesAreExact() {
        // Known limitation, kept visible: "суициде" (dative) is not "суицид",
        // and "self-harming" is not "self harm". Stemming would widen recall at
        // the cost of the substring false positives this task removes; a future
        // language-aware pass can revisit.
        assertEquals(Level.NONE, service.assess("INDIVIDUAL", "Мысли о суициде не уходят").level());
        assertEquals(Level.NONE, service.assess("INDIVIDUAL", "I have been self-harming again").level());
    }

    // -- MED tier: flagged for review, not routed -----------------------------

    @Test
    void singleMediumTermIsReviewNotCrisis() {
        Assessment a = service.assess("INDIVIDUAL", "Everything feels hopeless since the move");
        assertEquals(Level.REVIEW, a.level());
        assertEquals(1, a.score());
        assertTrue(a.needsReview());
        assertFalse(a.isCrisis());
        assertFalse(service.detect("INDIVIDUAL", "Everything feels hopeless since the move"));
    }

    @Test
    void threeMediumTermsReachTheCrisisThreshold() {
        Assessment a = service.assess("INDIVIDUAL",
                "I feel worthless and hopeless and I can’t go on like this");
        assertEquals(3, a.score());
        assertEquals(Level.CRISIS, a.level());
    }

    @Test
    void repeatedTermScoresOnce() {
        Assessment a = service.assess("INDIVIDUAL", "hopeless, hopeless, hopeless");
        assertEquals(1, a.score());
        assertEquals(Level.REVIEW, a.level());
    }

    @Test
    void russianAndArabicMediumTermsAreReview() {
        assertEquals(Level.REVIEW, service.assess("INDIVIDUAL", "У меня кризис в семье").level());
        assertEquals(Level.REVIEW, service.assess("INDIVIDUAL", "أمر في أزمة").level());
    }

    // -- generic words no longer count ----------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "This is urgent, please call me today",
            "It's an emergency with my housing",
            "Срочно нужна консультация",
            "Помогите найти психолога",
            "I feel anxious sometimes",
            "ساعدني في إيجاد جلسة"
    })
    void genericWordsScoreNothing(String description) {
        Assessment a = service.assess("INDIVIDUAL", description);
        assertEquals(Level.NONE, a.level(), description);
        assertEquals(0, a.score());
    }

    // -- word boundaries, not substrings --------------------------------------

    @Test
    void substringsInsideOtherWordsDoNotMatch() {
        // "worthlessness" and "hopelessly" contain MED terms as substrings
        assertEquals(Level.NONE, service.assess("INDIVIDUAL", "hopelessly disorganised, worthlessness aside").level());
        // but the bounded forms do
        assertEquals(Level.REVIEW, service.assess("INDIVIDUAL", "hopeless.").level());
    }

    // -- documented limitation: negation is not understood --------------------

    @Test
    void negationIsNotUnderstoodSoNegatedTermsStillScore() {
        // Deliberate: a false alarm costs a psychologist a glance; a miss can cost a life.
        assertTrue(service.detect("INDIVIDUAL", "I am not suicidal, just exhausted"));
    }

    @Test
    void blankDescriptionIsNormal() {
        assertEquals(Level.NONE, service.assess("INDIVIDUAL", "   ").level());
        assertEquals(Level.NONE, service.assess("INDIVIDUAL", null).level());
    }
}
