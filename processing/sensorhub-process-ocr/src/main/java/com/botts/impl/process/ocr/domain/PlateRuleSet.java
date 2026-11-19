/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.domain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable license-plate normalization and filtering rule set.
 *
 * <p>{@link #DEFAULT} implements the built-in Latin/ISO-6346 heuristics shipped with OSCAR.
 * Custom OCR Model &amp; Rule Packs can supply a {@code plate-rules.json} descriptor to support
 * non-Latin scripts (Cyrillic, Greek, Arabic, East Asian), diplomatic/commercial all-numeric
 * plates, or jurisdiction-specific regex allowlists without recompiling Java code.</p>
 */
public final class PlateRuleSet {

    private static final Set<String> BUILTIN_REJECT_EXACT = Set.of(
            "TARE", "MAXGROSS", "PAYLOAD", "NETWT", "CUFT", "CUM",
            "KGS", "LBS", "ISO", "HEAVY", "CAUTION", "HIGH", "CUBE"
    );

    private static final Pattern OSD_TOKEN = Pattern.compile(
            "^(?:CAM|CAMERA|CH|CHANNEL|LANE|GATE|PORTAL|RPM|FPS|KBPS|MBPS|NVR|DVR|IPC|RTSP|UTC|GMT|REC)\\d*$");

    private static final Pattern CONTAINER_SERIAL_FRAGMENT = Pattern.compile(
            "^\\d{5,7}[A-Z0-9]$|^[A-Z]\\d{6}$");

    private static final Pattern ISO_SIZE_TYPE_STENCIL = Pattern.compile(
            "^(?:20|22|25|40|42|45|L2|L5)[CGHPRUTVBSK][0-9][A-Z0-9]{0,2}$");

    private static final Pattern SIGNAGE_TOKEN = Pattern.compile(
            "(?:RING|TERMIN|ERMIN|RMINA|ERMUL|MINA\\d|DANGER|EURO|GROSS|CROSS|PAYLOAD)|^\\d{1,3}ING$");

    public static final PlateRuleSet DEFAULT = new PlateRuleSet(
            "builtin-latin",
            "Built-In Latin & ISO-6346 Filter",
            5,
            8,
            true,
            true,
            true,
            true,
            Pattern.compile("^[A-Z0-9\\s\\-]{5,12}$"),
            List.of(),
            BUILTIN_REJECT_EXACT
    );

    private final String ruleSetId;
    private final String description;
    private final int minLength;
    private final int maxLength;
    private final boolean asciiOnly;
    private final boolean requireLetter;
    private final boolean requireDigit;
    private final boolean rejectIsoContainerArtifacts;
    private final Pattern allowedRawPattern;
    private final List<Pattern> allowPatterns;
    private final Set<String> rejectExact;

    public PlateRuleSet(
            String ruleSetId,
            String description,
            int minLength,
            int maxLength,
            boolean asciiOnly,
            boolean requireLetter,
            boolean requireDigit,
            boolean rejectIsoContainerArtifacts,
            Pattern allowedRawPattern,
            List<Pattern> allowPatterns,
            Set<String> rejectExact
    ) {
        if (minLength < 2 || maxLength < minLength || maxLength > 24) {
            throw new IllegalArgumentException("PlateRuleSet length bounds must satisfy 2 <= minLength <= maxLength <= 24");
        }
        this.ruleSetId = (ruleSetId == null || ruleSetId.isBlank()) ? "custom" : ruleSetId.trim();
        this.description = (description == null || description.isBlank()) ? "Custom Plate Rule Set" : description.trim();
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.asciiOnly = asciiOnly;
        this.requireLetter = requireLetter;
        this.requireDigit = requireDigit;
        this.rejectIsoContainerArtifacts = rejectIsoContainerArtifacts;
        this.allowedRawPattern = allowedRawPattern;
        this.allowPatterns = allowPatterns == null ? List.of() : List.copyOf(allowPatterns);
        this.rejectExact = rejectExact == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(rejectExact));
    }

    public String getRuleSetId() {
        return ruleSetId;
    }

    public String getDescription() {
        return description;
    }

    public int getMinLength() {
        return minLength;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public boolean isAsciiOnly() {
        return asciiOnly;
    }

    public boolean isRequireLetter() {
        return requireLetter;
    }

    public boolean isRequireDigit() {
        return requireDigit;
    }

    public static PlateRuleSet fromJson(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            throw new IllegalArgumentException("plate-rules.json content is empty");
        }
        JsonElement parsed = JsonParser.parseString(jsonText);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("plate-rules.json root must be a JSON object");
        }
        return fromJsonObject(parsed.getAsJsonObject());
    }

    public static PlateRuleSet fromJsonObject(JsonObject obj) {
        String id = getString(obj, "ruleSetId", "custom-plate-rules");
        String desc = getString(obj, "description", "Custom Regional License Plate Rules");
        int minLen = getInt(obj, "minLength", 4);
        int maxLen = getInt(obj, "maxLength", 10);
        boolean ascii = getBoolean(obj, "asciiOnly", false);
        boolean reqLetter = getBoolean(obj, "requireLetter", false);
        boolean reqDigit = getBoolean(obj, "requireDigit", true);
        boolean rejectIso = getBoolean(obj, "rejectIsoContainerArtifacts", true);

        String rawRegex = getString(obj, "allowedRawRegex",
                ascii ? "^[A-Z0-9\\s\\-.]{3,16}$" : "^[\\p{L}\\p{N}\\s\\-.]{2,20}$");
        Pattern rawPat = Pattern.compile(rawRegex, Pattern.UNICODE_CHARACTER_CLASS);

        List<Pattern> allowPats = new ArrayList<>();
        if (obj.has("allowPatterns") && obj.get("allowPatterns").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("allowPatterns");
            for (JsonElement el : arr) {
                if (el != null && el.isJsonPrimitive()) {
                    String p = el.getAsString().trim();
                    if (!p.isEmpty()) {
                        allowPats.add(Pattern.compile(p, Pattern.UNICODE_CHARACTER_CLASS));
                    }
                }
            }
        }

        Set<String> rejects = new LinkedHashSet<>(BUILTIN_REJECT_EXACT);
        if (obj.has("rejectExact") && obj.get("rejectExact").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("rejectExact")) {
                if (el != null && el.isJsonPrimitive()) {
                    String token = el.getAsString().trim().toUpperCase(Locale.ROOT);
                    if (!token.isEmpty()) {
                        rejects.add(token);
                    }
                }
            }
        }

        return new PlateRuleSet(
                id, desc, minLen, maxLen, ascii, reqLetter, reqDigit, rejectIso, rawPat, allowPats, rejects
        );
    }

    public Optional<String> normalizePlate(String rawText) {
        if (rawText == null)
            return Optional.empty();
        String trimmed = rawText.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty())
            return Optional.empty();
        if (allowedRawPattern != null && !allowedRawPattern.matcher(trimmed).matches())
            return Optional.empty();

        StringBuilder sb = new StringBuilder(trimmed.length());
        int letters = 0;
        int digits = 0;
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            i += Character.charCount(cp);
            if (asciiOnly) {
                if (cp >= 'A' && cp <= 'Z') {
                    sb.append((char) cp);
                    letters++;
                } else if (cp >= '0' && cp <= '9') {
                    sb.append((char) cp);
                    digits++;
                }
            } else {
                if (Character.isLetter(cp)) {
                    sb.appendCodePoint(cp);
                    letters++;
                } else if (Character.isDigit(cp)) {
                    int numeric = Character.getNumericValue(cp);
                    if (numeric >= 0 && numeric <= 9 && cp > '9') {
                        sb.appendCodePoint(cp);
                    } else {
                        sb.appendCodePoint(cp);
                    }
                    digits++;
                }
            }
        }

        String compact = sb.toString();
        int cpLen = compact.codePointCount(0, compact.length());
        if (cpLen < minLength || cpLen > maxLength)
            return Optional.empty();

        if (requireLetter && letters == 0)
            return Optional.empty();
        if (requireDigit && digits == 0)
            return Optional.empty();

        if (rejectExact.contains(compact))
            return Optional.empty();
        if (OSD_TOKEN.matcher(compact).matches())
            return Optional.empty();

        if (rejectIsoContainerArtifacts) {
            if (requireLetter && CONTAINER_SERIAL_FRAGMENT.matcher(compact).matches())
                return Optional.empty();
            if (ISO_SIZE_TYPE_STENCIL.matcher(compact).matches())
                return Optional.empty();
            if (SIGNAGE_TOKEN.matcher(compact).find())
                return Optional.empty();
            if (compact.length() == 5 && letters == 4
                    && (compact.charAt(3) == 'U' || compact.charAt(3) == 'J'
                    || compact.charAt(3) == 'Z' || compact.charAt(3) == 'R')
                    && Character.isLetter(compact.charAt(0))
                    && Character.isLetter(compact.charAt(1))
                    && Character.isLetter(compact.charAt(2))) {
                return Optional.empty();
            }
        }

        if (!allowPatterns.isEmpty()) {
            boolean matchedAny = false;
            for (Pattern allow : allowPatterns) {
                if (allow.matcher(compact).matches()) {
                    matchedAny = true;
                    break;
                }
            }
            if (!matchedAny)
                return Optional.empty();
        }

        return Optional.of(compact);
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            String val = obj.get(key).getAsString().trim();
            if (!val.isEmpty())
                return val;
        }
        return fallback;
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsInt();
        }
        return fallback;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsBoolean();
        }
        return fallback;
    }
}
