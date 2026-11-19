/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.runtime;

import com.botts.impl.process.ocr.domain.PlateRuleSet;
import com.botts.impl.process.ocr.engine.OcrEngine;
import com.botts.impl.process.ocr.engine.OcrRead;
import com.botts.impl.process.ocr.engine.OnnxOcrEngine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.impl.utils.rad.eml.EmlArchiveExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Hub-scoped and process-default registry for hot-swappable OCR Model &amp; Rule Packs and
 * custom {@link OcrEngine} SPI archives.
 *
 * <p>Out of the box (and whenever reset), OSCAR uses the bundled {@code models/ocr} PP-OCRv3 +
 * PARSeq models and {@link PlateRuleSet#DEFAULT}. Uploaded custom overrides live outside the
 * read-only container image in {@code /var/lib/oscar/extensions/ocr/} so upgrades never overwrite
 * site-specific OCR configurations.</p>
 */
public final class OcrRuntimeRegistry {

    private static final Logger logger = LoggerFactory.getLogger(OcrRuntimeRegistry.class);
    private static final long MAX_UPLOAD_BYTES = 256L * 1024L * 1024L;

    private static final List<String> KNOWN_MODEL_FILENAMES = List.of(
            OnnxOcrEngine.DET_MODEL,
            OnnxOcrEngine.REC_MODEL,
            OnnxOcrEngine.DICT_FILE,
            OnnxOcrEngine.STENCIL_DET_MODEL,
            OnnxOcrEngine.PARSEQ_REC_MODEL,
            OnnxOcrEngine.PARSEQ_VOCAB_FILE
    );

    private static final Map<ISensorHub, OcrRuntimeRegistry> HUB_REGISTRIES = new WeakHashMap<>();
    private static final OcrRuntimeRegistry DEFAULT_REGISTRY = new OcrRuntimeRegistry();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static volatile PlateRuleSet globalActivePlateRules = PlateRuleSet.DEFAULT;

    private final Object installLock = new Object();
    private volatile LoadedOcrExtension activeCustom;
    private volatile String lastFailureMessage = "";

    private record LoadedOcrExtension(
            OcrEngine customEngine,
            URLClassLoader classLoader,
            Path extractedRoot,
            PlateRuleSet plateRules,
            OcrRuntimeStatus status
    ) implements AutoCloseable {
        @Override
        public void close() {
            if (customEngine != null) {
                try {
                    customEngine.close();
                } catch (Exception ignored) {
                }
            }
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException ignored) {
                }
            }
            if (extractedRoot != null) {
                EmlArchiveExtractor.deleteRecursivelyQuietly(extractedRoot);
            }
        }
    }

    public static synchronized OcrRuntimeRegistry forHub(ISensorHub hub) {
        if (hub == null) {
            return DEFAULT_REGISTRY;
        }
        return HUB_REGISTRIES.computeIfAbsent(hub, ignored -> new OcrRuntimeRegistry());
    }

    public static OcrRuntimeRegistry defaultRegistry() {
        return DEFAULT_REGISTRY;
    }

    public static PlateRuleSet getActivePlateRules() {
        return globalActivePlateRules;
    }

    public static boolean isSupportedUploadName(String fileName) {
        if (fileName == null)
            return false;
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        return EmlArchiveExtractor.isSupportedArchiveName(lower) || lower.endsWith(".json");
    }

    public OcrRuntimeStatus getStatus() {
        LoadedOcrExtension custom = activeCustom;
        if (custom != null) {
            if (lastFailureMessage == null || lastFailureMessage.isBlank()) {
                return custom.status();
            }
            OcrRuntimeStatus s = custom.status();
            return new OcrRuntimeStatus(
                    s.activeSource(),
                    s.customActive(),
                    s.packageKind(),
                    s.fileName(),
                    s.sha256(),
                    s.engineId(),
                    s.versionId(),
                    s.fastRoiSupported(),
                    s.plateRuleSetId(),
                    s.plateRuleDescription(),
                    s.modelFiles(),
                    s.loadedAt(),
                    "Reload rejected: " + lastFailureMessage
            );
        }
        Path resolved = OnnxOcrEngine.resolveModelDirectory(Paths.get("models/ocr"));
        List<String> builtinFiles = listPresentModels(resolved);
        boolean fastRoi = OnnxOcrEngine.hasTierZeroModelFiles(resolved);
        return OcrRuntimeStatus.builtinDefault(fastRoi, builtinFiles, lastFailureMessage);
    }

    /**
     * Returns an {@link OcrEngine} lease for a lane. When the lane uses the default model path
     * ({@code models/ocr}), calls dynamically route to any active custom OCR engine/model pack or
     * transparently fall back to the shared built-in {@link OnnxOcrEngine} with zero lane restart.
     */
    public OcrEngine acquireEngine(Path configuredModelDir) {
        boolean isDefaultPath = configuredModelDir == null
                || configuredModelDir.toString().isBlank()
                || "models/ocr".equals(configuredModelDir.toString().replace('\\', '/').trim());

        if (!isDefaultPath) {
            return OnnxOcrEngine.acquireShared(configuredModelDir);
        }
        OcrEngine builtinLease = OnnxOcrEngine.acquireShared(Paths.get("models/ocr"));
        return new DynamicRoutingEngine(this, builtinLease);
    }

    public OcrRuntimeStatus installFromFile(String fileName, Path uploadedFile) throws Exception {
        if (!isSupportedUploadName(fileName)) {
            String msg = "Unsupported OCR package file name (expected .zip, .tar.gz, .tgz, .tar, .jar, or .json): " + fileName;
            recordFailure(msg);
            throw new IllegalArgumentException(msg);
        }
        if (uploadedFile == null || !Files.isRegularFile(uploadedFile)) {
            String msg = "Uploaded OCR package does not exist: " + fileName;
            recordFailure(msg);
            throw new IOException(msg);
        }
        long size = Files.size(uploadedFile);
        if (size <= 0 || size > MAX_UPLOAD_BYTES) {
            String msg = "Invalid OCR package size (" + size + " bytes; limit is 256 MiB)";
            recordFailure(msg);
            throw new IOException(msg);
        }

        String sha256 = sha256Hex(uploadedFile);
        synchronized (installLock) {
            LoadedOcrExtension current = activeCustom;
            if (current != null
                    && sha256.equals(current.status().sha256())
                    && fileName.equals(current.status().fileName())) {
                lastFailureMessage = "";
                return current.status();
            }

            LoadedOcrExtension next;
            String lower = fileName.trim().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".json")) {
                next = loadRulesOnlyJson(fileName, sha256, uploadedFile);
            } else {
                next = loadArchivePackage(fileName, sha256, uploadedFile);
            }

            LoadedOcrExtension retired = activeCustom;
            activeCustom = next;
            globalActivePlateRules = next.plateRules();
            lastFailureMessage = "";

            if (this != DEFAULT_REGISTRY) {
                DEFAULT_REGISTRY.mirrorFrom(next);
            }

            logger.info("Activated custom OCR runtime '{}' (kind={}, engine={}, plateRules={}, sha256={})",
                    fileName,
                    next.status().packageKind(),
                    next.status().engineId(),
                    next.status().plateRuleSetId(),
                    sha256.substring(0, 12));

            if (retired != null) {
                retired.close();
            }
            return next.status();
        }
    }

    public OcrRuntimeStatus resetToDefault() {
        synchronized (installLock) {
            LoadedOcrExtension retired = activeCustom;
            activeCustom = null;
            globalActivePlateRules = PlateRuleSet.DEFAULT;
            lastFailureMessage = "";
            if (this != DEFAULT_REGISTRY) {
                DEFAULT_REGISTRY.clearMirror();
            }
            if (retired != null) {
                retired.close();
            }
            logger.info("Reset OCR runtime to built-in shipped default (PP-OCRv3 + PARSeq + ISO-6346)");
            return getStatus();
        }
    }

    private void mirrorFrom(LoadedOcrExtension ext) {
        this.activeCustom = ext;
        this.lastFailureMessage = "";
    }

    private void clearMirror() {
        this.activeCustom = null;
        this.lastFailureMessage = "";
    }

    private void recordFailure(String msg) {
        this.lastFailureMessage = msg == null ? "Unknown validation error" : msg;
    }

    private LoadedOcrExtension loadRulesOnlyJson(String fileName, String sha256, Path jsonFile) throws Exception {
        try {
            String raw = Files.readString(jsonFile, StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(raw);
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("OCR JSON upload must be a JSON object");
            }
            JsonObject obj = el.getAsJsonObject();
            JsonObject rulesObj = obj.has("plateRules") && obj.get("plateRules").isJsonObject()
                    ? obj.getAsJsonObject("plateRules")
                    : obj;
            PlateRuleSet rules = PlateRuleSet.fromJsonObject(rulesObj);
            Path builtinDir = OnnxOcrEngine.resolveModelDirectory(Paths.get("models/ocr"));
            boolean fastRoi = OnnxOcrEngine.hasTierZeroModelFiles(builtinDir);
            List<String> modelFiles = listPresentModels(builtinDir);
            String version = obj.has("version") && obj.get("version").isJsonPrimitive()
                    ? obj.get("version").getAsString().trim()
                    : "1.0.0";

            OcrRuntimeStatus status = new OcrRuntimeStatus(
                    OcrRuntimeStatus.SOURCE_CUSTOM,
                    true,
                    "plate-rules-pack",
                    fileName,
                    sha256,
                    "OnnxOcrEngine (Bundled Weights + Custom Plate Rules)",
                    version,
                    fastRoi,
                    rules.getRuleSetId(),
                    rules.getDescription(),
                    modelFiles,
                    Instant.now().toString(),
                    ""
            );
            return new LoadedOcrExtension(null, null, null, rules, status);
        } catch (Exception e) {
            recordFailure(e.getMessage());
            throw e;
        }
    }

    private LoadedOcrExtension loadArchivePackage(String fileName, String sha256, Path archiveFile) throws Exception {
        String configuredDataTmp = System.getProperty("oscar.data.tmpdir");
        String baseTmpDir = (configuredDataTmp != null && !configuredDataTmp.isBlank())
                ? configuredDataTmp
                : System.getProperty("java.io.tmpdir");
        Path tempBase = Paths.get(baseTmpDir, "oscar-ocr-runtime");
        EmlArchiveExtractor.ensurePrivateDirectory(tempBase);
        String token = (sha256.length() >= 16 ? sha256.substring(0, 16) : sha256)
                + "-" + SEQUENCE.incrementAndGet();
        Path extractDir = tempBase.resolve(token);

        URLClassLoader loader = null;
        OcrEngine customEngine = null;
        try {
            EmlArchiveExtractor.ExtractedBundle bundle = EmlArchiveExtractor.extractGeneric(archiveFile, fileName, extractDir);

            JsonObject manifestJson = findAndParseJson(extractDir, "manifest.json");
            JsonObject plateRulesJson = findAndParseJson(extractDir, "plate-rules.json");
            if (plateRulesJson == null && manifestJson != null
                    && manifestJson.has("plateRules") && manifestJson.get("plateRules").isJsonObject()) {
                plateRulesJson = manifestJson.getAsJsonObject("plateRules");
            }
            PlateRuleSet plateRules = plateRulesJson != null
                    ? PlateRuleSet.fromJsonObject(plateRulesJson)
                    : PlateRuleSet.DEFAULT;

            String manifestVersion = manifestJson != null && manifestJson.has("version") && manifestJson.get("version").isJsonPrimitive()
                    ? manifestJson.get("version").getAsString().trim()
                    : "";
            String manifestEngineId = manifestJson != null && manifestJson.has("packId") && manifestJson.get("packId").isJsonPrimitive()
                    ? manifestJson.get("packId").getAsString().trim()
                    : "";

            // Check Tier 2 first: Java SPI OcrEngine (.jar or archive with JARs)
            List<Path> candidateJars = new ArrayList<>(bundle.jarFiles());
            if (fileName.toLowerCase(Locale.ROOT).endsWith(".jar") && candidateJars.isEmpty()) {
                candidateJars.add(archiveFile);
            }
            String spiClassName = discoverSpiClassName(extractDir, candidateJars);
            if (spiClassName != null || !candidateJars.isEmpty()) {
                List<URL> urls = new ArrayList<>();
                urls.add(extractDir.toUri().toURL());
                for (Path jar : candidateJars) {
                    urls.add(jar.toUri().toURL());
                }
                loader = new URLClassLoader(urls.toArray(URL[]::new), OcrEngine.class.getClassLoader());
                if (spiClassName != null) {
                    Class<?> rawCls = Class.forName(spiClassName, true, loader);
                    if (!OcrEngine.class.isAssignableFrom(rawCls)) {
                        throw new IllegalArgumentException("Class " + spiClassName + " does not implement OcrEngine");
                    }
                    customEngine = (OcrEngine) rawCls.getDeclaredConstructor().newInstance();
                } else {
                    ServiceLoader<OcrEngine> sl = ServiceLoader.load(OcrEngine.class, loader);
                    var it = sl.iterator();
                    if (it.hasNext()) {
                        customEngine = it.next();
                    }
                }
                if (customEngine != null) {
                    customEngine.initialize(extractDir);
                    validateEngineDryRun(customEngine);
                    String engineId = !manifestEngineId.isEmpty() ? manifestEngineId : customEngine.getEngineId();
                    String versionId = !manifestVersion.isEmpty() ? manifestVersion : customEngine.getVersionId();
                    OcrRuntimeStatus status = new OcrRuntimeStatus(
                            OcrRuntimeStatus.SOURCE_CUSTOM,
                            true,
                            "ocr-engine-spi-jar",
                            fileName,
                            sha256,
                            engineId,
                            versionId,
                            customEngine.supportsFastRoiPass(),
                            plateRules.getRuleSetId(),
                            plateRules.getDescription(),
                            listPresentModels(extractDir),
                            Instant.now().toString(),
                            ""
                    );
                    return new LoadedOcrExtension(customEngine, loader, extractDir, plateRules, status);
                }
                loader.close();
                loader = null;
            }

            // Tier 1: Data-Only ONNX Model & Rule Pack (.zip / .tar.gz)
            Path modelRoot = locateModelDirectory(extractDir);
            boolean inheritBuiltin = manifestJson == null
                    || !manifestJson.has("inheritBuiltinModels")
                    || manifestJson.get("inheritBuiltinModels").getAsBoolean();

            if (modelRoot != null) {
                if (inheritBuiltin && !OnnxOcrEngine.hasCompleteModelFiles(modelRoot)) {
                    overlayMissingModelsFromBuiltin(modelRoot);
                }
                if (!OnnxOcrEngine.hasCompleteModelFiles(modelRoot)) {
                    throw new IllegalArgumentException(
                            "OCR model pack is missing required ONNX files (det.onnx, rec.onnx, dict.txt)");
                }
                customEngine = new OnnxOcrEngine(modelRoot);
                validateEngineDryRun(customEngine);
                List<String> presentModels = listPresentModels(modelRoot);
                String engineId = !manifestEngineId.isEmpty() ? manifestEngineId : "Custom ONNX Pack (" + fileName + ")";
                String versionId = !manifestVersion.isEmpty() ? manifestVersion : "1.0.0";
                OcrRuntimeStatus status = new OcrRuntimeStatus(
                        OcrRuntimeStatus.SOURCE_CUSTOM,
                        true,
                        "onnx-model-pack",
                        fileName,
                        sha256,
                        engineId,
                        versionId,
                        customEngine.supportsFastRoiPass(),
                        plateRules.getRuleSetId(),
                        plateRules.getDescription(),
                        presentModels,
                        Instant.now().toString(),
                        ""
                );
                return new LoadedOcrExtension(customEngine, null, extractDir, plateRules, status);
            }

            // Rules-only archive (contains plate-rules.json / manifest.json without ONNX weights)
            if (plateRulesJson != null) {
                EmlArchiveExtractor.deleteRecursivelyQuietly(extractDir);
                Path builtinDir = OnnxOcrEngine.resolveModelDirectory(Paths.get("models/ocr"));
                OcrRuntimeStatus status = new OcrRuntimeStatus(
                        OcrRuntimeStatus.SOURCE_CUSTOM,
                        true,
                        "plate-rules-pack",
                        fileName,
                        sha256,
                        !manifestEngineId.isEmpty() ? manifestEngineId : "OnnxOcrEngine (Bundled Weights + Custom Rules)",
                        !manifestVersion.isEmpty() ? manifestVersion : "1.0.0",
                        OnnxOcrEngine.hasTierZeroModelFiles(builtinDir),
                        plateRules.getRuleSetId(),
                        plateRules.getDescription(),
                        listPresentModels(builtinDir),
                        Instant.now().toString(),
                        ""
                );
                return new LoadedOcrExtension(null, null, null, plateRules, status);
            }

            throw new IllegalArgumentException(
                    "Archive contains neither ONNX model files (det.onnx/rec.onnx/dict.txt), plate-rules.json, nor an OcrEngine SPI implementation");
        } catch (Exception e) {
            if (customEngine != null) {
                try {
                    customEngine.close();
                } catch (Exception ignored) {
                }
            }
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                }
            }
            EmlArchiveExtractor.deleteRecursivelyQuietly(extractDir);
            recordFailure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            throw e;
        }
    }

    private static void validateEngineDryRun(OcrEngine engine) {
        BufferedImage dryRunImage = new BufferedImage(320, 96, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dryRunImage.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 320, 96);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));
            g.drawString("MSCU1234565", 24, 58);
        } finally {
            g.dispose();
        }
        List<OcrRead> reads = engine.recognize(dryRunImage);
        if (reads == null) {
            throw new IllegalStateException("OcrEngine.recognize(BufferedImage) violated contract by returning null");
        }
    }

    private static Path locateModelDirectory(Path extractDir) throws IOException {
        try (Stream<Path> walk = Files.walk(extractDir, 4)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return KNOWN_MODEL_FILENAMES.contains(n);
                    })
                    .map(Path::getParent)
                    .min(Comparator.comparingInt(Path::getNameCount))
                    .orElse(null);
        }
    }

    private static void overlayMissingModelsFromBuiltin(Path targetModelDir) throws IOException {
        Path builtinDir = OnnxOcrEngine.resolveModelDirectory(Paths.get("models/ocr"));
        if (!Files.isDirectory(builtinDir)) {
            return;
        }
        for (String modelName : KNOWN_MODEL_FILENAMES) {
            Path dest = targetModelDir.resolve(modelName);
            Path src = builtinDir.resolve(modelName);
            if (!Files.exists(dest) && Files.isRegularFile(src)) {
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static JsonObject findAndParseJson(Path root, String targetFileName) throws IOException {
        Path found;
        try (Stream<Path> walk = Files.walk(root, 3)) {
            found = walk.filter(Files::isRegularFile)
                    .filter(p -> targetFileName.equalsIgnoreCase(p.getFileName().toString()))
                    .min(Comparator.comparingInt(Path::getNameCount))
                    .orElse(null);
        }
        if (found == null) {
            return null;
        }
        JsonElement el = JsonParser.parseString(Files.readString(found, StandardCharsets.UTF_8));
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException(targetFileName + " must contain a JSON object");
        }
        return el.getAsJsonObject();
    }

    private static String discoverSpiClassName(Path extractDir, List<Path> jars) throws IOException {
        Path metaProps = extractDir.resolve("META-INF").resolve(OcrEngine.DESCRIPTOR_FILENAME);
        Path rootProps = extractDir.resolve(OcrEngine.DESCRIPTOR_FILENAME);
        for (Path p : List.of(metaProps, rootProps)) {
            if (Files.isRegularFile(p)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                }
                String cls = props.getProperty("class");
                if (cls != null && !cls.isBlank()) {
                    return cls.trim();
                }
            }
        }
        Path manifestFile = extractDir.resolve("META-INF").resolve("MANIFEST.MF");
        if (Files.isRegularFile(manifestFile)) {
            try (InputStream in = Files.newInputStream(manifestFile)) {
                Manifest mf = new Manifest(in);
                String attr = mf.getMainAttributes().getValue(OcrEngine.MANIFEST_CLASS_ATTRIBUTE);
                if (attr != null && !attr.isBlank()) {
                    return attr.trim();
                }
            }
        }
        for (Path jar : jars) {
            if (Files.isRegularFile(jar)) {
                try (JarFile jf = new JarFile(jar.toFile())) {
                    Manifest mf = jf.getManifest();
                    if (mf != null) {
                        String attr = mf.getMainAttributes().getValue(OcrEngine.MANIFEST_CLASS_ATTRIBUTE);
                        if (attr != null && !attr.isBlank()) {
                            return attr.trim();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static List<String> listPresentModels(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> present = new ArrayList<>();
        for (String name : KNOWN_MODEL_FILENAMES) {
            if (Files.isRegularFile(dir.resolve(name))) {
                present.add(name);
            }
        }
        return present;
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[16_384];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static final class DynamicRoutingEngine implements OcrEngine {
        private final OcrRuntimeRegistry registry;
        private final OcrEngine builtinLease;
        private volatile boolean closed = false;

        DynamicRoutingEngine(OcrRuntimeRegistry registry, OcrEngine builtinLease) {
            this.registry = registry;
            this.builtinLease = builtinLease;
        }

        private OcrEngine currentDelegate() {
            LoadedOcrExtension ext = registry.activeCustom;
            if (ext != null && ext.customEngine() != null) {
                return ext.customEngine();
            }
            return builtinLease;
        }

        @Override
        public List<OcrRead> recognize(BufferedImage frame) {
            return currentDelegate().recognize(frame);
        }

        @Override
        public List<OcrRead> recognize(BufferedImage frame, int detMaxSide) {
            return currentDelegate().recognize(frame, detMaxSide);
        }

        @Override
        public boolean supportsFastRoiPass() {
            return currentDelegate().supportsFastRoiPass();
        }

        @Override
        public List<OcrRead> recognizeFastRoi(BufferedImage frame) {
            return currentDelegate().recognizeFastRoi(frame);
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                builtinLease.close();
            }
        }
    }
}
