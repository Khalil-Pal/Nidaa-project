package com.humanitarian.platform.util;

import java.util.Locale;

public final class HelpTypeNormalizer {

    private HelpTypeNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "OTHER";
        }

        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "MEDICAL" -> "MEDICAL";
            case "FOOD" -> "FOOD";
            case "SHELTER" -> "SHELTER";
            case "WATER" -> "WATER";
            case "CLOTHING" -> "CLOTHING";
            case "PSYCHOLOGICAL" -> "PSYCHOLOGICAL";
            default -> "OTHER";
        };
    }
}
