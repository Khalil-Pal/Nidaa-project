package com.humanitarian.platform.util;

import java.util.Locale;

/**
 * Canonicalises a help type to one of the labels of the help_type database
 * enum. Unknown or missing input is an error, never a silent "OTHER": a
 * request stored as OTHER can never be matched (ProviderResourceService
 * supports only the five material types), so coercing would create a
 * permanently unassignable request without telling anyone.
 */
public final class HelpTypeNormalizer {

    public static final String ACCEPTED = "MEDICAL, FOOD, SHELTER, WATER, CLOTHING";

    private HelpTypeNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Help type is required. Accepted values: " + ACCEPTED);
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "MEDICAL" -> "MEDICAL";
            case "FOOD" -> "FOOD";
            case "SHELTER" -> "SHELTER";
            case "WATER" -> "WATER";
            case "CLOTHING" -> "CLOTHING";
            // Present in the database enum for legacy rows; not accepted from forms
            case "OTHER" -> "OTHER";
            default -> throw new IllegalArgumentException(
                    "Unknown help type '" + raw + "'. Accepted values: " + ACCEPTED);
        };
    }
}
