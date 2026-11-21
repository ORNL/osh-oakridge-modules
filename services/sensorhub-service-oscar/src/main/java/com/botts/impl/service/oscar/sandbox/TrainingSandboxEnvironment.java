/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import java.time.Instant;
import java.util.Locale;

/**
 * Runtime environment inspection and constants for the ephemeral OSCAR Hands-On
 * Training Sandbox ({@code OSCAR_SANDBOX_MODE=true}).
 *
 * <p>Outside sandbox mode ({@link #isSandboxMode()} == {@code false}), all sandbox
 * loopback emulators, streamers, seeders, and report watermarks are strictly inert
 * with zero background threads, zero open sockets, and zero memory overhead.</p>
 */
public final class TrainingSandboxEnvironment {

    public static final String ENV_SANDBOX_MODE = "OSCAR_SANDBOX_MODE";
    public static final String PROP_SANDBOX_MODE = "oscar.sandbox.mode";
    public static final String ENV_SANDBOX_PORT = "OSCAR_SANDBOX_PORT";
    public static final String ENV_SANDBOX_TTL_MINUTES = "OSCAR_SANDBOX_TTL_MINUTES";
    public static final String ENV_SANDBOX_STARTED_EPOCH = "OSCAR_SANDBOX_STARTED_EPOCH_SEC";

    public static final int DEFAULT_SANDBOX_HTTPS_PORT = 8444;
    public static final int DEFAULT_TTL_MINUTES = 120;
    public static final int DEFAULT_RAPISCAN_PORT_1 = 10099;
    public static final int DEFAULT_RAPISCAN_PORT_2 = 10100;
    public static final int DEFAULT_RTSP_PORT = 8554;

    public static final String WATERMARK_TEXT = "TRAINING SANDBOX — NON-OPERATIONAL";
    public static final String WATERMARK_BANNER = "TRAINING SANDBOX — NON-OPERATIONAL PRACTICE DATA (FLUSHED ON EXIT)";

    public static final String CONTAINER_1 = buildIso6346ContainerId("MSCU528572");
    public static final String CONTAINER_2 = buildIso6346ContainerId("OSCU482910");
    public static final String PLATE_1 = "TN482KMP";
    public static final String PLATE_2 = "VA917TRL";

    private static final long PROCESS_START_EPOCH_SEC = Instant.now().getEpochSecond();

    private TrainingSandboxEnvironment() {
    }

    public static boolean isSandboxMode() {
        String prop = System.getProperty(PROP_SANDBOX_MODE);
        if (prop != null && !prop.isBlank()) {
            return "true".equalsIgnoreCase(prop.trim()) || "1".equals(prop.trim());
        }
        String env = System.getenv(ENV_SANDBOX_MODE);
        return env != null && ("true".equalsIgnoreCase(env.trim()) || "1".equals(env.trim()));
    }

    public static int getSandboxHttpsPort() {
        return parseBoundedInt(
                firstNonBlank(System.getProperty("oscar.sandbox.port"), System.getenv(ENV_SANDBOX_PORT)),
                DEFAULT_SANDBOX_HTTPS_PORT,
                1024,
                65535);
    }

    public static int getTtlMinutes() {
        return parseBoundedInt(
                firstNonBlank(System.getProperty("oscar.sandbox.ttlMinutes"), System.getenv(ENV_SANDBOX_TTL_MINUTES)),
                DEFAULT_TTL_MINUTES,
                5,
                1440);
    }

    public static long getStartedEpochSeconds() {
        String raw = firstNonBlank(
                System.getProperty("oscar.sandbox.startedEpochSec"),
                System.getenv(ENV_SANDBOX_STARTED_EPOCH));
        if (raw != null && !raw.isBlank()) {
            try {
                long parsed = Long.parseLong(raw.trim());
                if (parsed > 1_700_000_000L) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return PROCESS_START_EPOCH_SEC;
    }

    public static long getExpiresEpochSeconds() {
        return getStartedEpochSeconds() + (getTtlMinutes() * 60L);
    }

    public static long getRemainingSeconds() {
        return Math.max(0L, getExpiresEpochSeconds() - Instant.now().getEpochSecond());
    }

    /**
     * Computes the exact ISO 6346 Modulo-11 check digit for a 10-character owner+serial prefix
     * (4 uppercase letters + 6 digits) so synthetic container stencils pass strict OCR validation.
     */
    public static int computeIso6346CheckDigit(String ownerAndSerial10) {
        if (ownerAndSerial10 == null || ownerAndSerial10.length() != 10) {
            throw new IllegalArgumentException("ISO 6346 prefix must be 10 characters: " + ownerAndSerial10);
        }
        String upper = ownerAndSerial10.toUpperCase(Locale.ROOT);
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char ch = upper.charAt(i);
            int val;
            if (i < 4) {
                if (ch < 'A' || ch > 'Z') {
                    throw new IllegalArgumentException("First 4 characters must be letters: " + upper);
                }
                // ISO 6346 letter weight mapping (skips multiples of 11: A=10, B=12..K=21, L=23..U=32, V=34..Z=38)
                int offset = ch - 'A';
                val = 10 + offset + ((offset + 1) / 10);
                // Exact check: A(0)->10, B(1)->12, ..., J(9)->20, K(10)->21, L(11)->23, ..., U(20)->32, V(21)->34, ..., Z(25)->38
                val = iso6346LetterValue(ch);
            } else {
                if (ch < '0' || ch > '9') {
                    throw new IllegalArgumentException("Characters 5..10 must be digits: " + upper);
                }
                val = ch - '0';
            }
            sum += val * (1 << i);
        }
        int rem = sum % 11;
        return rem == 10 ? 0 : rem;
    }

    public static String buildIso6346ContainerId(String ownerAndSerial10) {
        String upper = ownerAndSerial10.toUpperCase(Locale.ROOT);
        return upper + computeIso6346CheckDigit(upper);
    }

    private static int iso6346LetterValue(char c) {
        return switch (c) {
            case 'A' -> 10;
            case 'B' -> 12;
            case 'C' -> 13;
            case 'D' -> 14;
            case 'E' -> 15;
            case 'F' -> 16;
            case 'G' -> 17;
            case 'H' -> 18;
            case 'I' -> 19;
            case 'J' -> 20;
            case 'K' -> 21;
            case 'L' -> 23;
            case 'M' -> 24;
            case 'N' -> 25;
            case 'O' -> 26;
            case 'P' -> 27;
            case 'Q' -> 28;
            case 'R' -> 29;
            case 'S' -> 30;
            case 'T' -> 31;
            case 'U' -> 32;
            case 'V' -> 34;
            case 'W' -> 35;
            case 'X' -> 36;
            case 'Y' -> 37;
            case 'Z' -> 38;
            default -> throw new IllegalArgumentException("Unsupported ISO 6346 letter: " + c);
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static int parseBoundedInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
