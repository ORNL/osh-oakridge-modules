/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ISO 6346 freight container identification code parser, check-digit validator, and
 * OCR-ambiguity repair utility.
 *
 * <p>Format: 3 uppercase owner letters + 1 equipment category identifier ({@code U},
 * {@code J}, {@code Z}, {@code R}) + 6 serial digits + 1 check digit (11 characters total,
 * compact normalized form {@code CSQU3054383}).</p>
 */
public final class Iso6346 {

    public record Match(String compact, String formatted, boolean checksumValid, boolean checkDigitRepaired) {}

    public static final int CODE_LENGTH = 11;

    private static final Pattern CONTAINER_PATTERN = Pattern.compile("^[A-Z]{3}[UJZR]\\d{7}$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]");

    /**
     * Burned-in camera OSD timestamps (ISO dates, slash/dot dates, 24h clocks) stripped prior to
     * scanning 11-character windows so digits from timestamps never fuse with adjacent camera
     * labels into bogus container codes (e.g. {@code CAM 2026-03-28} -> {@code CAMZ0260328}).
     */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}"
                    + "|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4}"
                    + "|\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b");

    // ISO 6346 letter values count up from A=10 skipping multiples of 11 (11, 22, 33 unused)
    private static final int[] LETTER_VALUES = {
            10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24,
            25, 26, 27, 28, 29, 30, 31, 32, 34, 35, 36, 37, 38
    };

    private Iso6346() {
    }

    public static String stripTimestamps(String raw) {
        if (raw == null)
            return "";
        return TIMESTAMP.matcher(raw).replaceAll(" ");
    }

    public static String normalize(String raw) {
        if (raw == null)
            return "";
        return NON_ALNUM.matcher(stripTimestamps(raw).toUpperCase(Locale.ROOT)).replaceAll("");
    }

    public static boolean matchesPattern(String compact) {
        return compact != null && CONTAINER_PATTERN.matcher(compact).matches();
    }

    public static int computeCheckDigit(CharSequence first10) {
        if (first10 == null || first10.length() < 10)
            throw new IllegalArgumentException("Expected at least 10 characters");
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = Character.toUpperCase(first10.charAt(i));
            int val;
            if (i < 4) {
                if (c < 'A' || c > 'Z')
                    throw new IllegalArgumentException("Expected letter at index " + i + ": " + first10);
                if (i == 3 && !isCategory(c))
                    throw new IllegalArgumentException("Invalid category letter: " + c);
                val = LETTER_VALUES[c - 'A'];
            } else {
                if (c < '0' || c > '9')
                    throw new IllegalArgumentException("Expected digit at index " + i + ": " + first10);
                val = c - '0';
            }
            sum += val * (1 << i);
        }
        return (sum % 11) % 10;
    }

    public static boolean isValid(String candidate) {
        if (candidate == null)
            return false;
        String clean = NON_ALNUM.matcher(candidate.toUpperCase(Locale.ROOT)).replaceAll("");
        if (!matchesPattern(clean))
            return false;
        return computeCheckDigit(clean) == (clean.charAt(10) - '0');
    }

    public static String repairCheckDigit(String first10) {
        return first10 + (char) ('0' + computeCheckDigit(first10));
    }

    public static String format(String compact11) {
        if (compact11 == null || compact11.length() != CODE_LENGTH)
            return compact11;
        return compact11.substring(0, 4) + " " + compact11.substring(4, 10) + " " + compact11.substring(10);
    }

    /**
     * Scans {@code rawText} for the best ISO 6346 container match, preferring in order:
     * <ol>
     *   <li>An 11-char window (with positional glyph coercion) whose read check digit validates.</li>
     *   <li>A cleanly-read 10-char owner+category+serial prefix whose boxed check digit was
     *       misread or missed by the detector, repaired deterministically from characters 1..10.</li>
     *   <li>A structural pattern-only match whose category slot was genuinely read as a letter.</li>
     * </ol>
     */
    public static Optional<Match> extractBest(String rawText) {
        String normalized = normalize(rawText);
        if (normalized.length() < CODE_LENGTH - 1)
            return Optional.empty();

        Match repaired = null;
        Match patternOnly = null;

        for (int i = 0; i + CODE_LENGTH <= normalized.length(); i++) {
            String source = normalized.substring(i, i + CODE_LENGTH);
            String coerced = coerceByPosition(source);
            String validVariant = findChecksumValidVariant(source, coerced);
            if (validVariant != null) {
                return Optional.of(new Match(validVariant, format(validVariant), true, false));
            }
            if (repaired == null && prefixReadCleanly(source)) {
                String fixed = repairCheckDigit(source.substring(0, 10));
                repaired = new Match(fixed, format(fixed), true, true);
            }
            // Reject pattern-only candidates whose category identifier was manufactured from a digit
            if (patternOnly == null && matchesPattern(coerced) && !Character.isDigit(source.charAt(3))) {
                patternOnly = new Match(coerced, format(coerced), false, false);
            }
        }

        // Check-digit box missed altogether leaving a clean 10-character owner+category+serial read
        if (repaired == null) {
            for (int i = 0; i + CODE_LENGTH - 1 <= normalized.length(); i++) {
                String source = normalized.substring(i, i + CODE_LENGTH - 1);
                if (prefixReadCleanly(source)) {
                    String fixed = repairCheckDigit(source);
                    repaired = new Match(fixed, format(fixed), true, true);
                    break;
                }
            }
        }

        return Optional.ofNullable(repaired != null ? repaired : patternOnly);
    }

    private static boolean prefixReadCleanly(String source) {
        if (source == null || source.length() < 10)
            return false;
        for (int i = 0; i < 3; i++) {
            char c = source.charAt(i);
            if (c < 'A' || c > 'Z')
                return false;
        }
        if (!isCategory(source.charAt(3)))
            return false;
        for (int i = 4; i < 10; i++) {
            char c = source.charAt(i);
            if (c < '0' || c > '9')
                return false;
        }
        return true;
    }

    static String coerceByPosition(String window11) {
        char[] out = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            char c = window11.charAt(i);
            if (i < 4) {
                out[i] = digitToLetter(c);
            } else {
                out[i] = letterToDigit(c);
            }
        }
        return new String(out);
    }

    /**
     * Evaluates primary positional coercion first, then checks ambiguous {@code 0}/{@code O} ->
     * {@code O}/{@code Q}/{@code D} owner letters (indices 0..2) and {@code 0} -> {@code U}
     * category letter (index 3) strictly when the modulo-11 check digit validates.
     */
    private static String findChecksumValidVariant(String source, String coerced) {
        if (matchesPattern(coerced) && isValid(coerced))
            return coerced;

        String serialAndCheck = coerced.substring(4);
        for (int i = 0; i < serialAndCheck.length(); i++) {
            char d = serialAndCheck.charAt(i);
            if (d < '0' || d > '9')
                return null;
        }

        char[][] choices = new char[4][];
        for (int i = 0; i < 3; i++) {
            char s = source.charAt(i);
            char c = coerced.charAt(i);
            if (s == '0' || s == 'O') {
                choices[i] = new char[]{'O', 'Q', 'D'};
            } else if (c >= 'A' && c <= 'Z') {
                choices[i] = new char[]{c};
            } else {
                return null;
            }
        }
        char s3 = source.charAt(3);
        char c3 = coerced.charAt(3);
        if (isCategory(c3)) {
            choices[3] = new char[]{c3};
        } else if (s3 == '0' || s3 == 'O') {
            choices[3] = new char[]{'U'};
        } else {
            return null;
        }

        for (char c0 : choices[0]) {
            for (char c1 : choices[1]) {
                for (char c2 : choices[2]) {
                    for (char cat : choices[3]) {
                        String candidate = "" + c0 + c1 + c2 + cat + serialAndCheck;
                        if (isValid(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static char digitToLetter(char c) {
        return switch (c) {
            case '0' -> 'O';
            case '1' -> 'I';
            case '2' -> 'Z';
            case '5' -> 'S';
            case '6' -> 'G';
            case '8' -> 'B';
            default -> c;
        };
    }

    private static char letterToDigit(char c) {
        return switch (c) {
            case 'O', 'Q', 'D' -> '0';
            case 'I', 'L' -> '1';
            case 'Z' -> '2';
            case 'S' -> '5';
            case 'G' -> '6';
            case 'B' -> '8';
            default -> c;
        };
    }

    private static boolean isCategory(char c) {
        return c == 'U' || c == 'J' || c == 'Z' || c == 'R';
    }
}
