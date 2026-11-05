package org.sensorhub.impl.utils.rad.eml;

import org.sensorhub.api.ISensorHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Hub-scoped registry managing dynamically uploaded EML runtime plugins.
 */
public final class EmlRuntimeRegistry {
    private static final Logger logger = LoggerFactory.getLogger(EmlRuntimeRegistry.class);
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L;

    private static final Map<ISensorHub, EmlRuntimeRegistry> HUB_REGISTRIES = new WeakHashMap<>();
    private static final EmlRuntimeRegistry DEFAULT_REGISTRY = new EmlRuntimeRegistry();
    private static final AtomicLong EXTRACT_SEQUENCE = new AtomicLong();

    private final Object installLock = new Object();
    private final CopyOnWriteArrayList<Consumer<EmlRuntimeStatus>> listeners = new CopyOnWriteArrayList<>();
    private volatile EmlLoadedRuntime activeRuntime;
    private volatile EmlRuntimeStatus currentStatus = EmlRuntimeStatus.notLoaded("");

    EmlRuntimeRegistry() {}

    public static synchronized EmlRuntimeRegistry forHub(ISensorHub hub) {
        if (hub == null)
            return DEFAULT_REGISTRY;
        return HUB_REGISTRIES.computeIfAbsent(hub, ignored -> new EmlRuntimeRegistry());
    }

    public boolean isLoaded() {
        return activeRuntime != null && currentStatus.loaded();
    }

    public EmlRuntimeStatus getStatus() {
        return currentStatus;
    }

    public void addStatusListener(Consumer<EmlRuntimeStatus> listener) {
        if (listener != null)
            listeners.addIfAbsent(listener);
    }

    public void removeStatusListener(Consumer<EmlRuntimeStatus> listener) {
        if (listener != null)
            listeners.remove(listener);
    }

    public EmlRuntimeStatus installFromArchive(String fileName, Path archivePath) throws Exception {
        if (!EmlArchiveExtractor.isSupportedArchiveName(fileName)) {
            String msg = "Unsupported EML plugin file type: " + fileName;
            recordFailure(msg);
            throw new IllegalArgumentException(msg);
        }
        if (archivePath == null || !Files.isRegularFile(archivePath)) {
            String msg = "EML plugin archive does not exist: " + fileName;
            recordFailure(msg);
            throw new IOException(msg);
        }
        long size = Files.size(archivePath);
        if (size <= 0 || size > MAX_ARCHIVE_BYTES) {
            String msg = "Invalid EML plugin archive size (" + size + " bytes)";
            recordFailure(msg);
            throw new IOException(msg);
        }

        String sha256 = sha256Hex(archivePath);
        synchronized (installLock) {
            EmlLoadedRuntime current = activeRuntime;
            if (current != null && sha256.equals(current.getStatus().sha256())
                    && fileName.equals(current.getStatus().fileName())) {
                return current.getStatus();
            }

            String configuredDataTmp = System.getProperty("oscar.data.tmpdir");
            String baseTmpDir = (configuredDataTmp != null && !configuredDataTmp.isBlank())
                    ? configuredDataTmp
                    : System.getProperty("java.io.tmpdir");
            Path tempBase = Paths.get(baseTmpDir, "oscar-eml-runtime");
            EmlArchiveExtractor.ensurePrivateDirectory(tempBase);
            String dirToken = (sha256.length() >= 16 ? sha256.substring(0, 16) : sha256)
                    + "-" + EXTRACT_SEQUENCE.incrementAndGet();
            Path extractDir = tempBase.resolve(dirToken);

            EmlLoadedRuntime nextRuntime;
            try {
                var bundle = EmlArchiveExtractor.extract(archivePath, fileName, extractDir);
                nextRuntime = EmlLoadedRuntime.open(fileName, sha256, bundle);
            } catch (Exception e) {
                EmlArchiveExtractor.deleteRecursivelyQuietly(extractDir);
                recordFailure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                throw e;
            }

            EmlLoadedRuntime retired = activeRuntime;
            activeRuntime = nextRuntime;
            currentStatus = nextRuntime.getStatus();
            logger.info("Activated EML runtime plugin '{}' (version={}, model={}, sha256={})",
                    fileName,
                    currentStatus.versionId(),
                    currentStatus.collimatedModelId(),
                    sha256.substring(0, 12));

            if (retired != null) {
                retired.close();
            }
            notifyListeners(currentStatus);
            return currentStatus;
        }
    }

    public EmlEvaluationResult evaluate(EmlLaneParameters params, Stream<String> occupancyLines) throws Exception {
        EmlLoadedRuntime runtime = activeRuntime;
        if (runtime == null)
            return null;
        return runtime.evaluate(params, occupancyLines);
    }

    public void unload() {
        synchronized (installLock) {
            EmlLoadedRuntime retired = activeRuntime;
            activeRuntime = null;
            currentStatus = EmlRuntimeStatus.notLoaded("");
            if (retired != null)
                retired.close();
            notifyListeners(currentStatus);
        }
    }

    private void recordFailure(String message) {
        if (activeRuntime == null) {
            currentStatus = EmlRuntimeStatus.notLoaded(message);
        } else {
            EmlRuntimeStatus active = activeRuntime.getStatus();
            currentStatus = new EmlRuntimeStatus(
                    true,
                    active.fileName(),
                    active.sha256(),
                    active.versionId(),
                    active.collimatedModelId(),
                    active.uncollimatedModelId(),
                    active.loadedAt(),
                    "Reload rejected: " + message);
        }
        notifyListeners(currentStatus);
    }

    private void notifyListeners(EmlRuntimeStatus status) {
        for (Consumer<EmlRuntimeStatus> listener : listeners) {
            try {
                listener.accept(status);
            } catch (Exception e) {
                logger.warn("EML status listener threw exception", e);
            }
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[16_384];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
