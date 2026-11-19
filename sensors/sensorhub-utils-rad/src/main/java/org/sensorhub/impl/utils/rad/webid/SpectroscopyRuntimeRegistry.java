/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package org.sensorhub.impl.utils.rad.webid;

import org.sensorhub.api.ISensorHub;
import org.sensorhub.impl.utils.rad.eml.EmlArchiveExtractor;
import org.sensorhub.impl.utils.rad.model.WebIdAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * Hub-scoped registry for pluggable secondary-inspection spectroscopy nuclide classifiers
 * (Data-Only {@code isotope-library.json} packs or {@link SpectroscopyIdentificationAlgorithm}
 * {@code .jar} archives).
 */
public final class SpectroscopyRuntimeRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SpectroscopyRuntimeRegistry.class);
    private static final long MAX_UPLOAD_BYTES = 128L * 1024L * 1024L;

    private static final Map<ISensorHub, SpectroscopyRuntimeRegistry> HUB_REGISTRIES = new WeakHashMap<>();
    private static final SpectroscopyRuntimeRegistry DEFAULT_REGISTRY = new SpectroscopyRuntimeRegistry();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final Object installLock = new Object();
    private volatile LoadedSpectroscopy activeCustom;
    private volatile String configuredWebIdApiRoot = "https://full-spectrum.sandia.gov/api/v1/";
    private volatile String lastFailureMessage = "";

    private record LoadedSpectroscopy(
            SpectroscopyIdentificationAlgorithm algorithm,
            URLClassLoader classLoader,
            Path extractedRoot,
            String executionMode,
            SpectroscopyRuntimeStatus status
    ) implements AutoCloseable {
        @Override
        public void close() {
            if (algorithm != null) {
                try {
                    algorithm.close();
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

    public static synchronized SpectroscopyRuntimeRegistry forHub(ISensorHub hub) {
        if (hub == null) {
            return DEFAULT_REGISTRY;
        }
        return HUB_REGISTRIES.computeIfAbsent(hub, ignored -> new SpectroscopyRuntimeRegistry());
    }

    public static SpectroscopyRuntimeRegistry defaultRegistry() {
        return DEFAULT_REGISTRY;
    }

    public static boolean isSupportedUploadName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        return EmlArchiveExtractor.isSupportedArchiveName(lower) || lower.endsWith(".json");
    }

    public void setConfiguredWebIdApiRoot(String webIdApiRoot) {
        if (webIdApiRoot != null && !webIdApiRoot.isBlank()) {
            this.configuredWebIdApiRoot = webIdApiRoot.trim();
        }
    }

    public boolean isCustomLoaded() {
        return activeCustom != null;
    }

    public boolean isOverrideModeActive() {
        LoadedSpectroscopy custom = activeCustom;
        return custom != null && "override".equalsIgnoreCase(custom.executionMode());
    }

    public SpectroscopyRuntimeStatus getStatus() {
        LoadedSpectroscopy custom = activeCustom;
        if (custom != null) {
            if (lastFailureMessage == null || lastFailureMessage.isBlank()) {
                return custom.status();
            }
            SpectroscopyRuntimeStatus s = custom.status();
            return new SpectroscopyRuntimeStatus(
                    s.activeSource(),
                    s.customActive(),
                    s.packageKind(),
                    s.fileName(),
                    s.sha256(),
                    s.algorithmId(),
                    s.versionId(),
                    s.executionMode(),
                    s.isotopeCount(),
                    s.loadedAt(),
                    "Reload rejected: " + lastFailureMessage
            );
        }
        return SpectroscopyRuntimeStatus.builtinDefault(configuredWebIdApiRoot, lastFailureMessage);
    }

    public SpectroscopyRuntimeStatus setExecutionMode(String executionMode) {
        String normalized = IsotopeLibraryClassifier.normalizeExecutionMode(executionMode);
        synchronized (installLock) {
            LoadedSpectroscopy current = activeCustom;
            if (current == null) {
                return getStatus();
            }
            SpectroscopyRuntimeStatus s = current.status();
            SpectroscopyRuntimeStatus updatedStatus = new SpectroscopyRuntimeStatus(
                    s.activeSource(),
                    s.customActive(),
                    s.packageKind(),
                    s.fileName(),
                    s.sha256(),
                    s.algorithmId(),
                    s.versionId(),
                    normalized,
                    s.isotopeCount(),
                    s.loadedAt(),
                    ""
            );
            activeCustom = new LoadedSpectroscopy(
                    current.algorithm(),
                    current.classLoader(),
                    current.extractedRoot(),
                    normalized,
                    updatedStatus
            );
            return updatedStatus;
        }
    }

    public WebIdAnalysis evaluate(SpectroscopyAnalysisInput input) throws Exception {
        LoadedSpectroscopy custom = activeCustom;
        if (custom == null) {
            return null;
        }
        return custom.algorithm().analyze(input);
    }

    public SpectroscopyRuntimeStatus installFromFile(String fileName, Path uploadedFile, String requestedMode) throws Exception {
        if (!isSupportedUploadName(fileName)) {
            String msg = "Unsupported spectroscopy package file name (expected .json, .zip, .tar.gz, .tgz, .tar, or .jar): " + fileName;
            lastFailureMessage = msg;
            throw new IllegalArgumentException(msg);
        }
        if (uploadedFile == null || !Files.isRegularFile(uploadedFile)) {
            String msg = "Uploaded spectroscopy file does not exist: " + fileName;
            lastFailureMessage = msg;
            throw new IOException(msg);
        }
        long size = Files.size(uploadedFile);
        if (size <= 0 || size > MAX_UPLOAD_BYTES) {
            String msg = "Invalid spectroscopy package size (" + size + " bytes; limit is 128 MiB)";
            lastFailureMessage = msg;
            throw new IOException(msg);
        }

        String sha256 = sha256Hex(uploadedFile);
        synchronized (installLock) {
            LoadedSpectroscopy next;
            String lower = fileName.trim().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".json")) {
                next = loadJsonLibrary(fileName, sha256, uploadedFile, requestedMode);
            } else {
                next = loadArchive(fileName, sha256, uploadedFile, requestedMode);
            }

            LoadedSpectroscopy retired = activeCustom;
            activeCustom = next;
            lastFailureMessage = "";
            if (this != DEFAULT_REGISTRY) {
                DEFAULT_REGISTRY.activeCustom = next;
            }
            logger.info("Activated custom spectroscopy extension '{}' (kind={}, algo={}, mode={}, sha256={})",
                    fileName,
                    next.status().packageKind(),
                    next.status().algorithmId(),
                    next.status().executionMode(),
                    sha256.substring(0, 12));

            if (retired != null) {
                retired.close();
            }
            return next.status();
        }
    }

    public SpectroscopyRuntimeStatus resetToDefault() {
        synchronized (installLock) {
            LoadedSpectroscopy retired = activeCustom;
            activeCustom = null;
            lastFailureMessage = "";
            if (this != DEFAULT_REGISTRY) {
                DEFAULT_REGISTRY.activeCustom = null;
            }
            if (retired != null) {
                retired.close();
            }
            logger.info("Reset spectroscopy runtime to built-in shipped default (Sandia WebID + N42/Cambio)");
            return getStatus();
        }
    }

    private LoadedSpectroscopy loadJsonLibrary(String fileName, String sha256, Path jsonFile, String requestedMode) throws Exception {
        try {
            String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
            IsotopeLibraryClassifier classifier = IsotopeLibraryClassifier.fromJson(json);
            validateAlgorithmDryRun(classifier);
            String mode = (requestedMode != null && !requestedMode.isBlank())
                    ? IsotopeLibraryClassifier.normalizeExecutionMode(requestedMode)
                    : classifier.getDefaultExecutionMode();
            SpectroscopyRuntimeStatus status = new SpectroscopyRuntimeStatus(
                    SpectroscopyRuntimeStatus.SOURCE_CUSTOM,
                    true,
                    "isotope-library-json",
                    fileName,
                    sha256,
                    classifier.getAlgorithmId(),
                    classifier.getVersionId(),
                    mode,
                    classifier.getIsotopeCount(),
                    Instant.now().toString(),
                    ""
            );
            return new LoadedSpectroscopy(classifier, null, null, mode, status);
        } catch (Exception e) {
            lastFailureMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw e;
        }
    }

    private LoadedSpectroscopy loadArchive(String fileName, String sha256, Path archiveFile, String requestedMode) throws Exception {
        String configuredDataTmp = System.getProperty("oscar.data.tmpdir");
        String baseTmpDir = (configuredDataTmp != null && !configuredDataTmp.isBlank())
                ? configuredDataTmp
                : System.getProperty("java.io.tmpdir");
        Path tempBase = Paths.get(baseTmpDir, "oscar-spectroscopy-runtime");
        EmlArchiveExtractor.ensurePrivateDirectory(tempBase);
        String token = (sha256.length() >= 16 ? sha256.substring(0, 16) : sha256)
                + "-" + SEQUENCE.incrementAndGet();
        Path extractDir = tempBase.resolve(token);

        URLClassLoader loader = null;
        SpectroscopyIdentificationAlgorithm algo = null;
        try {
            EmlArchiveExtractor.ExtractedBundle bundle = EmlArchiveExtractor.extractGeneric(archiveFile, fileName, extractDir);
            List<Path> jars = new ArrayList<>(bundle.jarFiles());
            if (fileName.toLowerCase(Locale.ROOT).endsWith(".jar") && jars.isEmpty()) {
                jars.add(archiveFile);
            }
            String spiClass = discoverSpiClassName(extractDir, jars);
            if (spiClass != null || !jars.isEmpty()) {
                List<URL> urls = new ArrayList<>();
                urls.add(extractDir.toUri().toURL());
                for (Path j : jars) {
                    urls.add(j.toUri().toURL());
                }
                loader = new URLClassLoader(urls.toArray(URL[]::new), SpectroscopyIdentificationAlgorithm.class.getClassLoader());
                if (spiClass != null) {
                    Class<?> rawCls = Class.forName(spiClass, true, loader);
                    if (!SpectroscopyIdentificationAlgorithm.class.isAssignableFrom(rawCls)) {
                        throw new IllegalArgumentException("Class " + spiClass + " does not implement SpectroscopyIdentificationAlgorithm");
                    }
                    algo = (SpectroscopyIdentificationAlgorithm) rawCls.getDeclaredConstructor().newInstance();
                } else {
                    ServiceLoader<SpectroscopyIdentificationAlgorithm> sl =
                            ServiceLoader.load(SpectroscopyIdentificationAlgorithm.class, loader);
                    var it = sl.iterator();
                    if (it.hasNext()) {
                        algo = it.next();
                    }
                }
                if (algo != null) {
                    algo.initialize(extractDir);
                    validateAlgorithmDryRun(algo);
                    String mode = IsotopeLibraryClassifier.normalizeExecutionMode(requestedMode);
                    SpectroscopyRuntimeStatus status = new SpectroscopyRuntimeStatus(
                            SpectroscopyRuntimeStatus.SOURCE_CUSTOM,
                            true,
                            "spectroscopy-spi-jar",
                            fileName,
                            sha256,
                            algo.getAlgorithmId(),
                            algo.getVersionId(),
                            mode,
                            0,
                            Instant.now().toString(),
                            ""
                    );
                    return new LoadedSpectroscopy(algo, loader, extractDir, mode, status);
                }
                loader.close();
                loader = null;
            }

            // Otherwise check for an isotope-library.json inside the archive
            Path jsonPath;
            try (Stream<Path> walk = Files.walk(extractDir, 3)) {
                jsonPath = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .min(Comparator.comparingInt(Path::getNameCount))
                        .orElse(null);
            }
            if (jsonPath != null) {
                IsotopeLibraryClassifier classifier = IsotopeLibraryClassifier.fromJson(
                        Files.readString(jsonPath, StandardCharsets.UTF_8));
                validateAlgorithmDryRun(classifier);
                EmlArchiveExtractor.deleteRecursivelyQuietly(extractDir);
                String mode = (requestedMode != null && !requestedMode.isBlank())
                        ? IsotopeLibraryClassifier.normalizeExecutionMode(requestedMode)
                        : classifier.getDefaultExecutionMode();
                SpectroscopyRuntimeStatus status = new SpectroscopyRuntimeStatus(
                        SpectroscopyRuntimeStatus.SOURCE_CUSTOM,
                        true,
                        "isotope-library-pack",
                        fileName,
                        sha256,
                        classifier.getAlgorithmId(),
                        classifier.getVersionId(),
                        mode,
                        classifier.getIsotopeCount(),
                        Instant.now().toString(),
                        ""
                );
                return new LoadedSpectroscopy(classifier, null, null, mode, status);
            }

            throw new IllegalArgumentException(
                    "Spectroscopy archive contains neither a SpectroscopyIdentificationAlgorithm SPI implementation nor an isotope-library.json file");
        } catch (Exception e) {
            if (algo != null) {
                try {
                    algo.close();
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
            lastFailureMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw e;
        }
    }

    public static String buildSyntheticCs137N42Xml() {
        StringBuilder ch = new StringBuilder(4096);
        // 512 channels, 0..3072 keV (6 keV / channel); Cs-137 peak at 661.7 keV -> channel ~110
        for (int i = 0; i < 512; i++) {
            if (i > 0) {
                ch.append(' ');
            }
            int bg = 12;
            double dist = (i * 6.0 - 661.7) / 14.0;
            int signal = (int) Math.round(450.0 * Math.exp(-0.5 * dist * dist));
            ch.append(bg + signal);
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<RadInstrumentData xmlns=\"http://physics.nist.gov/N42/2011/N42\">\n"
                + "  <EnergyCalibration id=\"cal-1\"><Coefficients>0.0 6.0</Coefficients></EnergyCalibration>\n"
                + "  <RadMeasurement id=\"fg-1\">\n"
                + "    <MeasurementClassCode>Foreground</MeasurementClassCode>\n"
                + "    <RealTimeDuration>PT60.0S</RealTimeDuration>\n"
                + "    <Spectrum id=\"spec-1\" energyCalibrationReference=\"cal-1\">\n"
                + "      <LiveTimeDuration>PT60.0S</LiveTimeDuration>\n"
                + "      <LiveTime>PT60.0S</LiveTime>\n"
                + "      <ChannelData>" + ch + "</ChannelData>\n"
                + "    </Spectrum>\n"
                + "  </RadMeasurement>\n"
                + "</RadInstrumentData>\n";
    }

    private static void validateAlgorithmDryRun(SpectroscopyIdentificationAlgorithm algorithm) throws Exception {
        String n42 = buildSyntheticCs137N42Xml();
        SpectroscopyAnalysisInput dryRunInput = new SpectroscopyAnalysisInput(
                n42.getBytes(StandardCharsets.UTF_8),
                "dry-run-cs137.n42",
                null,
                null,
                n42,
                "auto",
                false
        );
        WebIdAnalysis out = algorithm.analyze(dryRunInput);
        if (out == null) {
            throw new IllegalStateException("SpectroscopyIdentificationAlgorithm.analyze() returned null on dry-run spectrum");
        }
    }

    private static String discoverSpiClassName(Path extractDir, List<Path> jars) throws IOException {
        Path metaProps = extractDir.resolve("META-INF").resolve(SpectroscopyIdentificationAlgorithm.DESCRIPTOR_FILENAME);
        Path rootProps = extractDir.resolve(SpectroscopyIdentificationAlgorithm.DESCRIPTOR_FILENAME);
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
                String attr = mf.getMainAttributes().getValue(SpectroscopyIdentificationAlgorithm.MANIFEST_CLASS_ATTRIBUTE);
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
                        String attr = mf.getMainAttributes().getValue(SpectroscopyIdentificationAlgorithm.MANIFEST_CLASS_ATTRIBUTE);
                        if (attr != null && !attr.isBlank()) {
                            return attr.trim();
                        }
                    }
                }
            }
        }
        return null;
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
}
