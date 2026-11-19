/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.extensions;

import com.botts.impl.service.oscar.AdjudicationCodeConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.sensorhub.impl.utils.rad.eml.EmlArchiveExtractor;
import org.sensorhub.impl.utils.rad.model.Adjudication;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Manages upgrade-safe Agency Adjudication Code &amp; Policy Packs stored in
 * {@code /var/lib/oscar/extensions/policy-packs/}.
 */
public final class PolicyPackManager {

    private static final String ACTIVE_FILE = "active-policy-pack.json";
    private static final String META_FILE = ".active-meta.json";
    private static final long MAX_POLICY_BYTES = 8L * 1024L * 1024L;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public record PolicyCodeItem(int code, String label, Map<String, String> labels) {}

    public record ActivePolicyPack(
            String packId,
            String version,
            String agencyName,
            String fileName,
            String sha256,
            List<PolicyCodeItem> codes,
            List<String> operatorChecklist,
            String activatedAt
    ) {}

    private final Path storageDir;
    private final Logger logger;
    private final Runnable onPolicyChanged;
    private final Object lock = new Object();
    private volatile ActivePolicyPack activePack;
    private volatile String lastError = "";

    public PolicyPackManager(Path storageDir, Logger logger, Runnable onPolicyChanged) {
        this.storageDir = storageDir.toAbsolutePath().normalize();
        this.logger = logger;
        this.onPolicyChanged = onPolicyChanged;
        try {
            EmlArchiveExtractor.ensurePrivateDirectory(this.storageDir);
        } catch (IOException e) {
            logger.warn("Unable to initialize policy pack storage directory {}: {}", this.storageDir, e.getMessage());
        }
    }

    public void restoreActiveOnStartup() {
        synchronized (lock) {
            Path jsonFile = storageDir.resolve(ACTIVE_FILE);
            if (!Files.isRegularFile(jsonFile)) {
                return;
            }
            try {
                byte[] bytes = Files.readAllBytes(jsonFile);
                String origName = ACTIVE_FILE;
                Path meta = storageDir.resolve(META_FILE);
                if (Files.isRegularFile(meta)) {
                    JsonObject m = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (m.has("fileName") && m.get("fileName").isJsonPrimitive()) {
                        origName = m.get("fileName").getAsString();
                    }
                }
                this.activePack = parseAndValidateJsonBytes(origName, bytes);
                logger.info("Restored active adjudication policy pack '{}' (version={}, codes={})",
                        activePack.packId(), activePack.version(), activePack.codes().size());
            } catch (Exception e) {
                logger.warn("Failed to restore active adjudication policy pack: {}", e.getMessage());
            }
        }
    }

    public List<AdjudicationCodeConfig> mergeEffectiveCodes(List<AdjudicationCodeConfig> baseConfigCodes) {
        Map<Integer, AdjudicationCodeConfig> merged = new LinkedHashMap<>();
        if (baseConfigCodes != null) {
            for (AdjudicationCodeConfig c : baseConfigCodes) {
                if (c != null && c.code > 11 && c.label != null && !c.label.isBlank()) {
                    AdjudicationCodeConfig copy = new AdjudicationCodeConfig();
                    copy.code = c.code;
                    copy.label = c.label;
                    merged.put(copy.code, copy);
                }
            }
        }
        ActivePolicyPack pack = activePack;
        if (pack != null) {
            for (PolicyCodeItem item : pack.codes()) {
                AdjudicationCodeConfig cfg = new AdjudicationCodeConfig();
                cfg.code = item.code();
                cfg.label = item.label();
                merged.put(cfg.code, cfg);
            }
        }
        return new ArrayList<>(merged.values());
    }

    public Map<String, Object> getStatus(List<AdjudicationCodeConfig> baseConfigCodes) {
        synchronized (lock) {
            ActivePolicyPack pack = activePack;
            boolean custom = pack != null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("activeSource", custom ? "custom" : "builtin");
            out.put("customActive", custom);
            out.put("builtinSummary", "ANSI/OSCAR Standard Disposition Codes (Codes 1-11, 9998, 9999)");
            out.put("baseConfiguredCount", baseConfigCodes == null ? 0 : baseConfigCodes.size());
            out.put("effectiveCustomCodes", mergeEffectiveCodes(baseConfigCodes));
            out.put("activePack", packToMap(pack));
            out.put("lastError", lastError);
            return out;
        }
    }

    private static Map<String, Object> packToMap(ActivePolicyPack pack) {
        if (pack == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("packId", pack.packId());
        m.put("version", pack.version());
        m.put("agencyName", pack.agencyName());
        m.put("fileName", pack.fileName());
        m.put("sha256", pack.sha256());
        List<Map<String, Object>> codes = new ArrayList<>();
        for (PolicyCodeItem c : pack.codes()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("code", c.code());
            cm.put("label", c.label());
            cm.put("labels", c.labels());
            codes.add(cm);
        }
        m.put("codes", codes);
        m.put("operatorChecklist", pack.operatorChecklist());
        m.put("activatedAt", pack.activatedAt());
        return m;
    }

    public Map<String, Object> installFromFile(String fileName, Path uploadedFile, List<AdjudicationCodeConfig> baseConfigCodes) throws Exception {
        if (fileName == null) {
            throw new IllegalArgumentException("Missing policy pack file name");
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".json") && !EmlArchiveExtractor.isSupportedArchiveName(lower)) {
            String msg = "Unsupported policy pack file type (expected .json or .zip): " + fileName;
            lastError = msg;
            throw new IllegalArgumentException(msg);
        }
        long size = Files.size(uploadedFile);
        if (size <= 0 || size > MAX_POLICY_BYTES) {
            String msg = "Invalid policy pack size (" + size + " bytes; limit is 8 MiB)";
            lastError = msg;
            throw new IllegalArgumentException(msg);
        }

        byte[] jsonBytes;
        if (lower.endsWith(".json")) {
            jsonBytes = Files.readAllBytes(uploadedFile);
        } else {
            Path tempExtract = Paths.get(System.getProperty("java.io.tmpdir"), "oscar-policy-extract-" + System.nanoTime());
            try {
                EmlArchiveExtractor.extractGeneric(uploadedFile, fileName, tempExtract);
                Path found;
                try (Stream<Path> walk = Files.walk(tempExtract, 3)) {
                    found = walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                            .min(Comparator.comparingInt(Path::getNameCount))
                            .orElse(null);
                }
                if (found == null) {
                    throw new IllegalArgumentException("Policy archive does not contain a .json policy descriptor");
                }
                jsonBytes = Files.readAllBytes(found);
            } finally {
                EmlArchiveExtractor.deleteRecursivelyQuietly(tempExtract);
            }
        }

        try {
            ActivePolicyPack parsed = parseAndValidateJsonBytes(fileName, jsonBytes);
            synchronized (lock) {
                EmlArchiveExtractor.ensurePrivateDirectory(storageDir);
                Path tmp = storageDir.resolve(ACTIVE_FILE + ".tmp");
                Files.write(tmp, jsonBytes);
                moveAtomically(tmp, storageDir.resolve(ACTIVE_FILE));

                JsonObject meta = new JsonObject();
                meta.addProperty("fileName", fileName);
                meta.addProperty("activatedAt", parsed.activatedAt());
                Path metaTmp = storageDir.resolve(META_FILE + ".tmp");
                Files.writeString(metaTmp, GSON.toJson(meta) + "\n", StandardCharsets.UTF_8);
                moveAtomically(metaTmp, storageDir.resolve(META_FILE));

                this.activePack = parsed;
                this.lastError = "";
            }
            if (onPolicyChanged != null) {
                onPolicyChanged.run();
            }
            logger.info("Activated adjudication policy pack '{}' (version={}, codes={})",
                    parsed.packId(), parsed.version(), parsed.codes().size());
            return getStatus(baseConfigCodes);
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw e;
        }
    }

    public Map<String, Object> resetToDefault(List<AdjudicationCodeConfig> baseConfigCodes) throws IOException {
        synchronized (lock) {
            this.activePack = null;
            this.lastError = "";
            Files.deleteIfExists(storageDir.resolve(ACTIVE_FILE));
            Files.deleteIfExists(storageDir.resolve(META_FILE));
        }
        if (onPolicyChanged != null) {
            onPolicyChanged.run();
        }
        logger.info("Reset adjudication policy pack to built-in standard disposition codes");
        return getStatus(baseConfigCodes);
    }

    public String exportCurrentPolicyJson(List<AdjudicationCodeConfig> baseConfigCodes) {
        ActivePolicyPack pack = activePack;
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("packId", pack != null ? pack.packId() : "oscar-site-policy");
        doc.put("version", pack != null ? pack.version() : "1.0.0");
        doc.put("agencyName", pack != null ? pack.agencyName() : "OSCAR Portal Deployment");
        List<Map<String, Object>> codeEntries = new ArrayList<>();
        for (AdjudicationCodeConfig c : mergeEffectiveCodes(baseConfigCodes)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", c.code);
            item.put("label", c.label);
            codeEntries.add(item);
        }
        doc.put("codes", codeEntries);
        doc.put("operatorChecklist", pack != null ? pack.operatorChecklist() : List.of(
                "Verify vehicle container or license plate match against camera keyframes.",
                "Attach secondary handheld N42 spectrum when gamma/neutron threshold is exceeded."
        ));
        return GSON.toJson(doc) + "\n";
    }

    private static ActivePolicyPack parseAndValidateJsonBytes(String fileName, byte[] jsonBytes) throws Exception {
        String text = new String(jsonBytes, StandardCharsets.UTF_8);
        JsonElement rootEl = JsonParser.parseString(text);
        if (!rootEl.isJsonObject()) {
            throw new IllegalArgumentException("Policy pack JSON must have an object root");
        }
        JsonObject root = rootEl.getAsJsonObject();
        String packId = getString(root, "packId", "custom-policy-pack");
        String version = getString(root, "version", "1.0.0");
        String agency = getString(root, "agencyName", "Custom Agency Policy");

        if (!root.has("codes") || !root.get("codes").isJsonArray()) {
            throw new IllegalArgumentException("Policy pack JSON must include a 'codes' array");
        }
        JsonArray codesArr = root.getAsJsonArray("codes");
        Set<Integer> seenCodes = new LinkedHashSet<>();
        List<PolicyCodeItem> items = new ArrayList<>();
        for (JsonElement el : codesArr) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject cObj = el.getAsJsonObject();
            if (!cObj.has("code") || !cObj.get("code").isJsonPrimitive()) {
                throw new IllegalArgumentException("Each policy code entry must include an integer 'code'");
            }
            int code = cObj.get("code").getAsInt();
            if (code <= 11) {
                throw new IllegalArgumentException("Custom disposition code " + code + " must be greater than 11 (codes 0-11 are built-in)");
            }
            if (code == Adjudication.EXTENDED_OCCUPANCY_GAMMA_CODE || code == Adjudication.EML_NON_THREAT_CODE) {
                throw new IllegalArgumentException("Disposition code " + code + " is reserved for automated system adjudications");
            }
            String label = getString(cObj, "label", "");
            if (label.isEmpty()) {
                throw new IllegalArgumentException("Disposition code " + code + " must have a non-empty 'label'");
            }
            if (!seenCodes.add(code)) {
                throw new IllegalArgumentException("Duplicate disposition code in policy pack: " + code);
            }
            Map<String, String> localized = new LinkedHashMap<>();
            if (cObj.has("labels") && cObj.get("labels").isJsonObject()) {
                for (var entry : cObj.getAsJsonObject("labels").entrySet()) {
                    if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                        localized.put(entry.getKey(), entry.getValue().getAsString().trim());
                    }
                }
            }
            items.add(new PolicyCodeItem(code, label, localized));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Policy pack must define at least one valid custom disposition code (> 11)");
        }

        List<String> checklist = new ArrayList<>();
        if (root.has("operatorChecklist") && root.get("operatorChecklist").isJsonArray()) {
            for (JsonElement chk : root.getAsJsonArray("operatorChecklist")) {
                if (chk != null && chk.isJsonPrimitive()) {
                    String step = chk.getAsString().trim();
                    if (!step.isEmpty()) {
                        checklist.add(step);
                    }
                }
            }
        }

        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jsonBytes));
        return new ActivePolicyPack(
                packId,
                version,
                agency,
                fileName,
                sha256,
                List.copyOf(items),
                List.copyOf(checklist),
                Instant.now().toString()
        );
    }

    private static void moveAtomically(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            String val = obj.get(key).getAsString().trim();
            if (!val.isEmpty()) {
                return val;
            }
        }
        return fallback;
    }
}
