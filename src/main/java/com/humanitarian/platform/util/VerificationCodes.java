package com.humanitarian.platform.util;

import java.security.SecureRandom;

/**
 * One-time codes sent by email (registration, password reset, password change)
 * and the masking used when the address is echoed back (C-3). Previously three
 * private copies of each.
 */
public final class VerificationCodes {

    /** Unambiguous upper-case letters and digits: no 0/O, 1/I. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private VerificationCodes() {
    }

    public static String generate(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** {@code someone@example.org} becomes {@code so***@example.org}; very short local parts are hidden entirely. */
    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return "***" + email.substring(at);
        return email.substring(0, 2) + "***" + email.substring(at);
    }
}
