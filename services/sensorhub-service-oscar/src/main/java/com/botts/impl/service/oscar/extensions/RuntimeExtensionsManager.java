/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.extensions;

import com.botts.impl.process.ocr.runtime.OcrRuntimeRegistry;
import com.botts.impl.process.ocr.runtime.OcrRuntimeStatus;
import com.botts.impl.service.oscar.AdjudicationCodeConfig;
import com.botts.impl.service.oscar.OSCARServiceConfig;
import com.botts.impl.service.oscar.eml.EmlPluginHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.impl.utils.rad.eml.EmlArchiveExtractor;
import org.sensorhub.impl.utils.rad.eml.EmlRuntimeRegistry;
import org.sensorhub.impl.utils.rad.security.DynamicTrustManagerRegistry;
import org.sensorhub.impl.utils.rad.webid.SpectroscopyRuntimeRegistry;
import org.sensorhub.impl.utils.rad.webid.SpectroscopyRuntimeStatus;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Coordinates upgrade-safe persistence, validation, hot-swapping, and reset-to-default
 * across OSCAR's pluggable runtime extensions:
 * <ul>
 *   <li>OCR Model &amp; Rule Packs / {@code OcrEngine} SPI ({@code extensions/ocr/})</li>
 *   <li>Offline Regional Basemap {@code .pmtiles} Packs ({@code archive/maps/} + {@code extensions/maps/})</li>
 *   <li>Secondary-Inspection Spectroscopy Libraries &amp; Classifiers ({@code extensions/spectroscopy/})</li>
 *   <li>Trusted Enterprise X.509 CA Certificates ({@code extensions/trusted-certs/})</li>
 *   <li>Agency Adjudication Code &amp; Policy Packs ({@code extensions/policy-packs/})</li>
 *   <li>EML Primary-Scan Alarm Assessment Plugins ({@code eml-plugin/})</li>
 * </ul>
 */
public final class RuntimeExtensionsManager {

    public static final String EXTENSIONS_ROOT_PROPERTY = "oscar.extensions.dir";
    public static final String ARCHIVE_MAPS_PROPERTY = "oscar.archive.maps.dir";
    private static final String ACTIVE_MARKER = ".active-file";
    private static final String MODE_MARKER = ".execution-mode";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final ISensorHub hub;
    private final OSCARServiceConfig oscarConfig;
    private final EmlPluginHandler emlPluginHandler;
    private final Logger logger;

    private final Path extensionsRoot;
    private final Path ocrDir;
    private final Path spectroscopyDir;
    private final Path trustedCertsDir;
    private final OfflineMapManager offlineMapManager;
    private final PolicyPackManager policyPackManager;

    public RuntimeExtensionsManager(
            ISensorHub hub,
            OSCARServiceConfig oscarConfig,
            EmlPluginHandler emlPluginHandler,
            Logger logger,
            Runnable onPolicyChanged
    ) {
        this(
                hub,
                oscarConfig,
                emlPluginHandler,
                logger,
                resolveDefaultExtensionsRoot(),
                resolveDefaultArchiveMapsDir(),
                onPolicyChanged
        );
    }

    public RuntimeExtensionsManager(
            ISensorHub hub,
            OSCARServiceConfig oscarConfig,
            EmlPluginHandler emlPluginHandler,
            Logger logger,
            Path extensionsRoot,
            Path archiveMapsDir,
            Runnable onPolicyChanged
    ) {
        this.hub = hub;
        this.oscarConfig = oscarConfig;
        this.emlPluginHandler = emlPluginHandler;
        this.logger = logger;
        this.extensionsRoot = extensionsRoot.toAbsolutePath().normalize();
        this.ocrDir = this.extensionsRoot.resolve("ocr");
        this.spectroscopyDir = this.extensionsRoot.resolve("spectroscopy");
        this.trustedCertsDir = this.extensionsRoot.resolve("trusted-certs");

        try {
            EmlArchiveExtractor.ensurePrivateDirectory(this.extensionsRoot);
            EmlArchiveExtractor.ensurePrivateDirectory(this.ocrDir);
            EmlArchiveExtractor.ensurePrivateDirectory(this.spectroscopyDir);
            EmlArchiveExtractor.ensurePrivateDirectory(this.trustedCertsDir);
        } catch (IOException e) {
            logger.warn("Unable to initialize runtime extensions directories under {}: {}",
                    this.extensionsRoot, e.getMessage());
        }

        if (oscarConfig != null && oscarConfig.webIdApiRoot != null && !oscarConfig.webIdApiRoot.isBlank()) {
            SpectroscopyRuntimeRegistry.forHub(hub).setConfiguredWebIdApiRoot(oscarConfig.webIdApiRoot);
        }

        this.offlineMapManager = new OfflineMapManager(this.extensionsRoot.resolve("maps"), archiveMapsDir, logger);
        this.policyPackManager = new PolicyPackManager(this.extensionsRoot.resolve("policy-packs"), logger, onPolicyChanged);
    }

    private static Path resolveDefaultExtensionsRoot() {
        String override = System.getProperty(EXTENSIONS_ROOT_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        return Paths.get("extensions");
    }

    private static Path resolveDefaultArchiveMapsDir() {
        String override = System.getProperty(ARCHIVE_MAPS_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        return resolveDefaultExtensionsRoot().resolve("maps-storage");
    }

    public OfflineMapManager getOfflineMapManager() {
        return offlineMapManager;
    }

    public PolicyPackManager getPolicyPackManager() {
        return policyPackManager;
    }

    public List<AdjudicationCodeConfig> getEffectiveCustomAdjudicationCodes() {
        List<AdjudicationCodeConfig> base = oscarConfig != null ? oscarConfig.customAdjudicationCodes : List.of();
        return policyPackManager.mergeEffectiveCodes(base);
    }

    /**
     * Restores all persisted custom runtime extensions during OSCAR startup so upgrades never
     * overwrite or drop site-configured OCR models, regional maps, spectroscopy packs, CAs, or policies.
     */
    public void restoreAllOnStartup() {
        restoreOcrOnStartup();
        restoreSpectroscopyOnStartup();
        restoreTrustedCertsOnStartup();
        offlineMapManager.restoreActiveOnStartup();
        policyPackManager.restoreActiveOnStartup();
    }

    private void restoreOcrOnStartup() {
        String activeName = readMarker(ocrDir.resolve(ACTIVE_MARKER));
        if (activeName == null) {
            return;
        }
        Path file = ocrDir.resolve(activeName);
        if (!Files.isRegularFile(file)) {
            logger.warn("Persisted custom OCR package '{}' missing from {}; keeping built-in default", activeName, ocrDir);
            return;
        }
        try {
            OcrRuntimeRegistry.forHub(hub).installFromFile(activeName, file);
        } catch (Exception e) {
            logger.error("Failed restoring persisted OCR package '{}': {}", activeName, e.getMessage());
        }
    }

    private void restoreSpectroscopyOnStartup() {
        String activeName = readMarker(spectroscopyDir.resolve(ACTIVE_MARKER));
        if (activeName == null) {
            return;
        }
        Path file = spectroscopyDir.resolve(activeName);
        if (!Files.isRegularFile(file)) {
            logger.warn("Persisted custom spectroscopy package '{}' missing from {}; keeping built-in default",
                    activeName, spectroscopyDir);
            return;
        }
        String mode = readMarker(spectroscopyDir.resolve(MODE_MARKER));
        try {
            SpectroscopyRuntimeRegistry.forHub(hub).installFromFile(activeName, file, mode);
        } catch (Exception e) {
            logger.error("Failed restoring persisted spectroscopy package '{}': {}", activeName, e.getMessage());
        }
    }

    private void restoreTrustedCertsOnStartup() {
        try {
            DynamicTrustManagerRegistry.reloadFromDirectory(trustedCertsDir);
        } catch (Exception e) {
            logger.error("Failed restoring custom trusted CA certificates from {}: {}", trustedCertsDir, e.getMessage());
        }
    }

    public Map<String, Object> getFullStatus(String servletRootPrefix) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ocr", ocrStatusToMap(OcrRuntimeRegistry.forHub(hub).getStatus()));
        root.put("maps", offlineMapManager.getAdminStatus(servletRootPrefix));
        root.put("spectroscopy", spectroscopyStatusToMap(SpectroscopyRuntimeRegistry.forHub(hub).getStatus()));
        root.put("trustedCerts", trustStatusToMap(DynamicTrustManagerRegistry.getStatus()));
        root.put("policyPacks", policyPackManager.getStatus(
                oscarConfig != null ? oscarConfig.customAdjudicationCodes : List.of()));
        var emlSt = emlPluginHandler != null
                ? emlPluginHandler.getStatus()
                : EmlRuntimeRegistry.forHub(hub).getStatus();
        root.put("eml", emlStatusToMap(emlSt));
        return root;
    }

    private static Map<String, Object> ocrStatusToMap(OcrRuntimeStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeSource", s.activeSource());
        m.put("customActive", s.customActive());
        m.put("packageKind", s.packageKind());
        m.put("fileName", s.fileName());
        m.put("sha256", s.sha256());
        m.put("engineId", s.engineId());
        m.put("versionId", s.versionId());
        m.put("fastRoiSupported", s.fastRoiSupported());
        m.put("plateRuleSetId", s.plateRuleSetId());
        m.put("plateRuleDescription", s.plateRuleDescription());
        m.put("modelFiles", s.modelFiles());
        m.put("loadedAt", s.loadedAt() != null ? s.loadedAt().toString() : null);
        m.put("lastError", s.lastError());
        return m;
    }

    private static Map<String, Object> spectroscopyStatusToMap(SpectroscopyRuntimeStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeSource", s.activeSource());
        m.put("customActive", s.customActive());
        m.put("packageKind", s.packageKind());
        m.put("fileName", s.fileName());
        m.put("sha256", s.sha256());
        m.put("algorithmId", s.algorithmId());
        m.put("versionId", s.versionId());
        m.put("executionMode", s.executionMode());
        m.put("isotopeCount", s.isotopeCount());
        m.put("loadedAt", s.loadedAt() != null ? s.loadedAt().toString() : null);
        m.put("lastError", s.lastError());
        return m;
    }

    private static Map<String, Object> trustStatusToMap(DynamicTrustManagerRegistry.TrustStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeSource", s.activeSource());
        m.put("customActive", s.customActive());
        m.put("systemTrustedIssuerCount", s.systemTrustedIssuerCount());
        m.put("customCertificateCount", s.customCertificateCount());
        List<Map<String, Object>> certs = new java.util.ArrayList<>();
        for (var c : s.customCertificates()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id());
            cm.put("fileName", c.fileName());
            cm.put("subject", c.subject());
            cm.put("issuer", c.issuer());
            cm.put("serialHex", c.serialHex());
            cm.put("notBefore", c.notBefore());
            cm.put("notAfter", c.notAfter());
            cm.put("sha256Fingerprint", c.sha256Fingerprint());
            cm.put("caCertificate", c.caCertificate());
            certs.add(cm);
        }
        m.put("customCertificates", certs);
        m.put("generation", s.generation());
        m.put("lastError", s.lastError());
        return m;
    }

    private static Map<String, Object> emlStatusToMap(org.sensorhub.impl.utils.rad.eml.EmlRuntimeStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", s.loaded());
        m.put("customActive", s.loaded());
        m.put("activeSource", s.loaded() ? "custom" : "unconfigured");
        m.put("fileName", s.fileName());
        m.put("sha256", s.sha256());
        m.put("versionId", s.versionId());
        m.put("collimatedModelId", s.collimatedModelId());
        m.put("uncollimatedModelId", s.uncollimatedModelId());
        m.put("loadedAt", s.loadedAt() != null ? s.loadedAt().toString() : null);
        m.put("lastError", s.lastError());
        return m;
    }

    // --- OCR Upload & Reset ---

    public OcrRuntimeStatus uploadOcrPackage(String rawName, InputStream in) throws Exception {
        String safeName = EmlPluginHandler.sanitizeOrNull(rawName);
        if (safeName == null || !OcrRuntimeRegistry.isSupportedUploadName(safeName)) {
            throw new IllegalArgumentException("Invalid OCR package filename: " + rawName);
        }
        EmlArchiveExtractor.ensurePrivateDirectory(ocrDir);
        Path staging = ocrDir.resolve("." + safeName + ".uploading." + System.nanoTime());
        Path dest = ocrDir.resolve(safeName);
        try {
            streamBoundedToFile(in, staging, 256L * 1024L * 1024L);
            OcrRuntimeStatus status = OcrRuntimeRegistry.forHub(hub).installFromFile(safeName, staging);
            moveAtomically(staging, dest);
            // Re-record clean canonical path after atomic move
            writeMarker(ocrDir.resolve(ACTIVE_MARKER), safeName);
            pruneOtherFiles(ocrDir, safeName);
            return status;
        } catch (Exception e) {
            Files.deleteIfExists(staging);
            throw e;
        }
    }

    public OcrRuntimeStatus resetOcrToDefault() throws IOException {
        Files.deleteIfExists(ocrDir.resolve(ACTIVE_MARKER));
        pruneOtherFiles(ocrDir, null);
        return OcrRuntimeRegistry.forHub(hub).resetToDefault();
    }

    // --- Spectroscopy Upload, Mode & Reset ---

    public SpectroscopyRuntimeStatus uploadSpectroscopyPackage(String rawName, String mode, InputStream in) throws Exception {
        String safeName = EmlPluginHandler.sanitizeOrNull(rawName);
        if (safeName == null || !SpectroscopyRuntimeRegistry.isSupportedUploadName(safeName)) {
            throw new IllegalArgumentException("Invalid spectroscopy package filename: " + rawName);
        }
        EmlArchiveExtractor.ensurePrivateDirectory(spectroscopyDir);
        Path staging = spectroscopyDir.resolve("." + safeName + ".uploading." + System.nanoTime());
        Path dest = spectroscopyDir.resolve(safeName);
        try {
            streamBoundedToFile(in, staging, 128L * 1024L * 1024L);
            SpectroscopyRuntimeStatus status = SpectroscopyRuntimeRegistry.forHub(hub).installFromFile(safeName, staging, mode);
            moveAtomically(staging, dest);
            writeMarker(spectroscopyDir.resolve(ACTIVE_MARKER), safeName);
            writeMarker(spectroscopyDir.resolve(MODE_MARKER), status.executionMode());
            pruneOtherFiles(spectroscopyDir, safeName);
            return status;
        } catch (Exception e) {
            Files.deleteIfExists(staging);
            throw e;
        }
    }

    public SpectroscopyRuntimeStatus setSpectroscopyMode(String mode) throws IOException {
        SpectroscopyRuntimeStatus updated = SpectroscopyRuntimeRegistry.forHub(hub).setExecutionMode(mode);
        if (updated.customActive()) {
            writeMarker(spectroscopyDir.resolve(MODE_MARKER), updated.executionMode());
        }
        return updated;
    }

    public SpectroscopyRuntimeStatus resetSpectroscopyToDefault() throws IOException {
        Files.deleteIfExists(spectroscopyDir.resolve(ACTIVE_MARKER));
        Files.deleteIfExists(spectroscopyDir.resolve(MODE_MARKER));
        pruneOtherFiles(spectroscopyDir, null);
        return SpectroscopyRuntimeRegistry.forHub(hub).resetToDefault();
    }

    // --- Trusted CA Certificates Upload, Delete & Reset ---

    public DynamicTrustManagerRegistry.TrustStatus uploadTrustedCertificate(String rawName, InputStream in) throws Exception {
        String safeName = EmlPluginHandler.sanitizeOrNull(rawName);
        if (safeName == null) {
            throw new IllegalArgumentException("Invalid certificate filename: " + rawName);
        }
        String lower = safeName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".pem") && !lower.endsWith(".crt") && !lower.endsWith(".cer")) {
            throw new IllegalArgumentException("Trusted CA upload must end with .pem, .crt, or .cer: " + safeName);
        }
        byte[] rawBytes = readBoundedBytes(in, 2L * 1024L * 1024L);
        DynamicTrustManagerRegistry.parseAndValidateCertificates(safeName, rawBytes);

        EmlArchiveExtractor.ensurePrivateDirectory(trustedCertsDir);
        Path tmp = trustedCertsDir.resolve("." + safeName + ".tmp");
        Path dest = trustedCertsDir.resolve(safeName);
        Files.write(tmp, rawBytes);
        moveAtomically(tmp, dest);
        return DynamicTrustManagerRegistry.reloadFromDirectory(trustedCertsDir);
    }

    public DynamicTrustManagerRegistry.TrustStatus deleteTrustedCertificate(String rawFileName) throws Exception {
        String safeName = EmlPluginHandler.sanitizeOrNull(rawFileName);
        if (safeName == null) {
            throw new IllegalArgumentException("Invalid certificate filename: " + rawFileName);
        }
        Files.deleteIfExists(trustedCertsDir.resolve(safeName));
        return DynamicTrustManagerRegistry.reloadFromDirectory(trustedCertsDir);
    }

    public DynamicTrustManagerRegistry.TrustStatus resetTrustedCertificatesToDefault() throws Exception {
        if (Files.isDirectory(trustedCertsDir)) {
            try (Stream<Path> s = Files.list(trustedCertsDir)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        return DynamicTrustManagerRegistry.resetToSystemDefault();
    }

    // --- Policy Pack Upload & Reset ---

    public Map<String, Object> uploadPolicyPack(String rawName, InputStream in) throws Exception {
        String safeName = EmlPluginHandler.sanitizeOrNull(rawName);
        if (safeName == null) {
            throw new IllegalArgumentException("Invalid policy pack filename: " + rawName);
        }
        Path tmp = Files.createTempFile("oscar-policy-upload-", "-" + safeName);
        try {
            streamBoundedToFile(in, tmp, 8L * 1024L * 1024L);
            return policyPackManager.installFromFile(
                    safeName,
                    tmp,
                    oscarConfig != null ? oscarConfig.customAdjudicationCodes : List.of());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public Map<String, Object> resetPolicyPack() throws IOException {
        return policyPackManager.resetToDefault(
                oscarConfig != null ? oscarConfig.customAdjudicationCodes : List.of());
    }

    // --- EML Upload & Unload ---

    public Object uploadEmlPlugin(String rawName, InputStream in) throws Exception {
        if (emlPluginHandler == null) {
            throw new IllegalStateException("EML plugin handler is not initialized");
        }
        String safeName = EmlPluginHandler.sanitizeOrNull(rawName);
        if (safeName == null || !emlPluginHandler.isValidFileType(safeName, "application/octet-stream")) {
            throw new IllegalArgumentException("Invalid EML plugin archive name: " + rawName);
        }
        try (OutputStream out = emlPluginHandler.handleUpload(safeName)) {
            byte[] buf = new byte[65_536];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > 256L * 1024L * 1024L) {
                    throw new IllegalArgumentException("EML plugin upload exceeds 256 MiB limit");
                }
                out.write(buf, 0, n);
            }
        }
        if (!emlPluginHandler.handleFile(safeName)) {
            String err = emlPluginHandler.getStatus().lastError();
            throw new IllegalArgumentException(err != null && !err.isBlank() ? err : "Failed validating EML plugin archive");
        }
        return emlPluginHandler.getStatus();
    }

    public Object unloadEmlPlugin() throws IOException {
        EmlRuntimeRegistry.forHub(hub).unload();
        if (emlPluginHandler != null && Files.isDirectory(emlPluginHandler.getStorageDir())) {
            try (Stream<Path> s = Files.list(emlPluginHandler.getStorageDir())) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        if (oscarConfig != null) {
            oscarConfig.emlPluginPath = null;
        }
        return EmlRuntimeRegistry.forHub(hub).getStatus();
    }

    // --- Starter Templates ---

    public byte[] buildOcrStarterTemplateZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("packId", "custom-regional-ocr-v1");
            manifest.put("version", "1.0.0");
            manifest.put("inheritBuiltinModels", true);
            manifest.put("notes", "Place optional det.onnx, rec.onnx, dict.txt, stencil_det_fp32.onnx, parseq_rec_int8.onnx, parseq_vocab.txt alongside manifest.json and plate-rules.json.");
            writeZipText(zos, "manifest.json", GSON.toJson(manifest) + "\n");

            Map<String, Object> plateRules = new LinkedHashMap<>();
            plateRules.put("ruleSetId", "regional-multilingual-v1");
            plateRules.put("description", "Regional Multilingual & Commercial Numeric Plate Filter");
            plateRules.put("minLength", 4);
            plateRules.put("maxLength", 10);
            plateRules.put("asciiOnly", false);
            plateRules.put("requireLetter", false);
            plateRules.put("requireDigit", true);
            plateRules.put("rejectIsoContainerArtifacts", true);
            plateRules.put("allowedRawRegex", "^[\\p{L}\\p{N}\\s\\-.]{4,14}$");
            plateRules.put("allowPatterns", List.of());
            plateRules.put("rejectExact", List.of("TARE", "MAXGROSS", "PAYLOAD", "NETWT"));
            writeZipText(zos, "plate-rules.json", GSON.toJson(plateRules) + "\n");

            String readme = "# OSCAR Compliant OCR Model & Rule Pack Template\n\n"
                    + "1. Edit `plate-rules.json` to customize regional license-plate character scripts, lengths, or regexes.\n"
                    + "2. Optionally add custom ONNX weights (`det.onnx`, `rec.onnx`, `dict.txt`, `stencil_det_fp32.onnx`, `parseq_rec_int8.onnx`, `parseq_vocab.txt`).\n"
                    + "   When `inheritBuiltinModels` is `true`, any omitted ONNX model automatically inherits OSCAR's shipped default.\n"
                    + "3. Zip these files and upload via `OSCAR Viewer -> Runtime Extensions -> OCR Models & Engines`.\n";
            writeZipText(zos, "README.md", readme);
        }
        return baos.toByteArray();
    }

    public String buildIsotopeLibraryTemplateJson() {
        Map<String, Object> lib = new LinkedHashMap<>();
        lib.put("schemaVersion", 1);
        lib.put("libraryId", "site-secondary-isotopes-v1");
        lib.put("version", "1.0.0");
        lib.put("executionMode", "fallback");
        lib.put("defaultWindowKeV", 22.0);
        lib.put("defaultMinSnr", 3.5);
        lib.put("isotopes", List.of(
                Map.of("name", "Cs-137", "category", "Industrial", "energiesKeV", List.of(661.7), "windowKeV", 20.0, "minSnr", 3.5),
                Map.of("name", "Co-60", "category", "Industrial", "energiesKeV", List.of(1173.2, 1332.5), "windowKeV", 24.0, "minSnr", 3.5),
                Map.of("name", "I-131", "category", "Medical", "energiesKeV", List.of(364.5), "windowKeV", 18.0, "minSnr", 3.5),
                Map.of("name", "Tc-99m", "category", "Medical", "energiesKeV", List.of(140.5), "windowKeV", 14.0, "minSnr", 4.0),
                Map.of("name", "K-40", "category", "NORM", "energiesKeV", List.of(1460.8), "windowKeV", 28.0, "minSnr", 3.5),
                Map.of("name", "Am-241", "category", "Industrial", "energiesKeV", List.of(59.5), "windowKeV", 12.0, "minSnr", 4.0),
                Map.of("name", "U-235", "category", "SNM", "energiesKeV", List.of(185.7), "windowKeV", 14.0, "minSnr", 4.5)
        ));
        return GSON.toJson(lib) + "\n";
    }

    public String buildPolicyPackTemplateJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("packId", "agency-disposition-policy-v1");
        doc.put("version", "1.0.0");
        doc.put("agencyName", "Port Authority Secondary Inspection");
        doc.put("codes", List.of(
                Map.of("code", 101, "label", "Verified Medical Isotope Patient Permit"),
                Map.of("code", 102, "label", "Manifested Agricultural / Ceramic NORM Cargo"),
                Map.of("code", 103, "label", "Escalated to Mobile Radiation Response Team")
        ));
        doc.put("operatorChecklist", List.of(
                "Confirm container or license plate against OCR crop and manifest.",
                "Upload handheld secondary N42 spectrum before clearing Industrial/SNM alarms."
        ));
        return GSON.toJson(doc) + "\n";
    }

    // --- File utilities ---

    private static void writeZipText(ZipOutputStream zos, String entryName, String content) throws IOException {
        ZipEntry e = new ZipEntry(entryName);
        zos.putNextEntry(e);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void streamBoundedToFile(InputStream in, Path target, long maxBytes) throws IOException {
        long written = 0L;
        byte[] buf = new byte[65_536];
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                written += n;
                if (written > maxBytes) {
                    throw new IllegalArgumentException("Upload exceeds maximum allowed size (" + maxBytes + " bytes)");
                }
                out.write(buf, 0, n);
            }
        }
        if (written == 0L) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
    }

    private static byte[] readBoundedBytes(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[16_384];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > maxBytes) {
                throw new IllegalArgumentException("Upload exceeds maximum allowed size (" + maxBytes + " bytes)");
            }
            baos.write(buf, 0, n);
        }
        if (total == 0L) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        return baos.toByteArray();
    }

    private static String readMarker(Path marker) {
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            String raw = Files.readString(marker, StandardCharsets.UTF_8).trim();
            return raw.isEmpty() ? null : raw;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMarker(Path marker, String value) throws IOException {
        Path tmp = marker.resolveSibling(marker.getFileName().toString() + ".tmp");
        Files.writeString(tmp, value + "\n", StandardCharsets.UTF_8);
        moveAtomically(tmp, marker);
    }

    private static void pruneOtherFiles(Path dir, String keepFileName) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(".")) {
                    continue;
                }
                if (keepFileName == null || !n.equals(keepFileName)) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void moveAtomically(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
