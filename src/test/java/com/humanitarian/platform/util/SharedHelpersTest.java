package com.humanitarian.platform.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C-3: the helpers that replaced three private copies each. */
class SharedHelpersTest {

    @Test
    void codesHaveTheRequestedLengthAndOnlyUnambiguousCharacters() {
        for (int i = 0; i < 200; i++) {
            String code = VerificationCodes.generate(8);
            assertEquals(8, code.length());
            assertTrue(code.chars().allMatch(c -> VerificationCodes.ALPHABET.indexOf(c) >= 0), code);
        }
        assertEquals(6, VerificationCodes.generate(6).length());
        assertFalse(VerificationCodes.ALPHABET.contains("0"));
        assertFalse(VerificationCodes.ALPHABET.contains("O"));
        assertFalse(VerificationCodes.ALPHABET.contains("1"));
        assertFalse(VerificationCodes.ALPHABET.contains("I"));
    }

    @Test
    void maskEmailKeepsTwoCharactersAndTheDomain() {
        assertEquals("so***@example.org", VerificationCodes.maskEmail("someone@example.org"));
        assertEquals("***@example.org", VerificationCodes.maskEmail("ab@example.org"));
        assertEquals("***@example.org", VerificationCodes.maskEmail("a@example.org"));
    }

    @Test
    void transitionsOnlyMoveForwardAndClosedStatesAreFinal() {
        assertTrue(RequestTransitions.allows("PENDING", "ASSIGNED"));
        assertTrue(RequestTransitions.allows("PENDING", "CANCELLED"));
        assertTrue(RequestTransitions.allows("ASSIGNED", "COMPLETED"));
        assertTrue(RequestTransitions.allows("ASSIGNED", "CANCELLED"));
        assertFalse(RequestTransitions.allows("PENDING", "COMPLETED"));
        assertFalse(RequestTransitions.allows("ASSIGNED", "PENDING"));
        assertFalse(RequestTransitions.allows("COMPLETED", "CANCELLED"));
        assertFalse(RequestTransitions.allows("CANCELLED", "ASSIGNED"));
        assertFalse(RequestTransitions.allows("BOGUS", "ASSIGNED"));
    }
}
