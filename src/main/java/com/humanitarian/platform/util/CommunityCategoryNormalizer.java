package com.humanitarian.platform.util;

import java.util.Locale;

public final class CommunityCategoryNormalizer {

    private CommunityCategoryNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UPDATE";
        }

        String normalized = raw.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "_");

        return switch (normalized) {
            case "UPDATE", "UPDATES" -> "UPDATE";
            case "SUCCESS_STORY", "SUCCESS_STORIES" -> "SUCCESS_STORIES";
            case "QUESTION", "QUESTIONS" -> "QUESTION";
            case "TIP_ADVICE", "TIPS_ADVICE" -> "TIPS_ADVICE";
            case "EVENT", "EVENTS" -> "EVENTS";
            case "RESOURCE", "RESOURCES" -> "RESOURCES";
            case "GRATITUDE" -> "GRATITUDE";
            case "INFO", "INFORMATION" -> "INFO";
            default -> throw new IllegalArgumentException(
                    "Invalid community category. Accepted values are: " +
                            "UPDATE, SUCCESS_STORIES, QUESTION, TIPS_ADVICE, " +
                            "EVENTS, RESOURCES, GRATITUDE, INFO");
        };
    }
}
