package com.humanitarian.platform.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B-2: unknown input is refused instead of becoming the unassignable OTHER. */
class HelpTypeNormalizerTest {

    @ParameterizedTest
    @ValueSource(strings = {"FOOD", "food", " Food ", "MEDICAL", "shelter", "WATER", "clothing"})
    void knownTypesAreCanonicalised(String raw) {
        String result = HelpTypeNormalizer.normalize(raw);
        assertEquals(raw.trim().toUpperCase(), result);
    }

    @Test
    void legacyOtherIsStillRecognisedForExistingRows() {
        assertEquals("OTHER", HelpTypeNormalizer.normalize("other"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GROCERIES", "PSYCHOLOGICAL", "F00D", ""})
    void unknownOrBlankTypesAreRejected(String raw) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HelpTypeNormalizer.normalize(raw));
        assertTrue(ex.getMessage().contains("Accepted values"));
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> HelpTypeNormalizer.normalize(null));
    }
}
