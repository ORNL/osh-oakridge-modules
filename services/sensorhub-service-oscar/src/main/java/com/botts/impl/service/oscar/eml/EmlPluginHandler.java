package com.botts.impl.service.oscar.eml;

import com.botts.impl.service.oscar.IFileHandler;
import com.botts.impl.service.oscar.OSCARServiceConfig;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.impl.utils.rad.eml.EmlArchiveExtractor;
import org.sensorhub.impl.utils.rad.eml.EmlRuntimeRegistry;
import org.sensorhub.impl.utils.rad.eml.EmlRuntimeStatus;
import org.slf4j.Logger;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.stream.Stream;

/**
 * Manages administrator upload and durable private persistence of the external
 * LLNL EML plugin archive inside OSCAR state without exposing it over HTTP buckets.
 */
public class EmlPluginHandler implements IFileHandler {
    public static final String STORAGE_DIR_PROPERTY = "oscar.eml.storage.dir";
    private static final String DEFAULT_STORAGE_DIR_NAME = "eml-plugin";
    private static final String ACTIVE_MARKER_FILE = ".active-plugin";

    private final ISensorHub hub;
    private final Logger logger;
    private final OSCARServiceConfig oscarConfig;
    private final Path storageDir;

    public EmlPluginHandler(ISensorHub hub, Logger logger, OSCARServiceConfig oscarConfig) {
        this(hub, logger, oscarConfig, resolveDefaultStorageDir());
    }

    public EmlPluginHandler(ISensorHub hub, Logger logger, OSCARServiceConfig oscarConfig, Path storageDir) {
        this.hub = hub;
        this.logger = logger;
        this.oscarConfig = oscarConfig;
        this.storageDir = storageDir.toAbsolutePath().normalize();
        try {
            EmlArchiveExtractor.ensurePrivateDirectory(this.storageDir);
        } catch (IOException e) {
            logger.warn("Unable to initialize EML plugin storage directory {}: {}",
                    this.storageDir, e.getMessage());
        }
    }

    private static Path resolveDefaultStorageDir() {
        String override = System.getProperty(STORAGE_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        return Paths.get(DEFAULT_STORAGE_DIR_NAME);
    }

    public Path getStorageDir() {
        return storageDir;
    }

    public EmlRuntimeStatus getStatus() {
        return EmlRuntimeRegistry.forHub(hub).getStatus();
    }

    public void restoreConfiguredOrActivePlugin(String configuredFileName) {
        String candidate = sanitizeOrNull(configuredFileName);
        if (candidate == null || !Files.isRegularFile(storageDir.resolve(candidate))) {
            candidate = readActiveMarker();
        }
        if (candidate == null) {
            return;
        }
        Path archivePath = storageDir.resolve(candidate);
        if (!Files.isRegularFile(archivePath)) {
            logger.warn("Configured EML plugin '{}' was not found in {}", candidate, storageDir);
            return;
        }
        if (handleFile(candidate) && oscarConfig != null && (oscarConfig.emlPluginPath == null || oscarConfig.emlPluginPath.isBlank())) {
            oscarConfig.emlPluginPath = candidate;
        }
    }

    @Override
    public boolean isValidFileType(String fileName, String mimeType) {
        String safe = sanitizeOrNull(fileName);
        return safe != null && EmlArchiveExtractor.isSupportedArchiveName(safe);
    }

    @Override
    public OutputStream handleUpload(String filename) throws DataStoreException {
        String safeName = sanitizeOrNull(filename);
        if (safeName == null || !EmlArchiveExtractor.isSupportedArchiveName(safeName)) {
            throw new DataStoreException("Invalid EML plugin archive name: " + filename);
        }
        try {
            EmlArchiveExtractor.ensurePrivateDirectory(storageDir);
            Path staging = storageDir.resolve(safeName + ".uploading");
            Path destination = storageDir.resolve(safeName);
            OutputStream rawOut = Files.newOutputStream(staging);
            restrictOwnerReadWrite(staging);

            return new FilterOutputStream(rawOut) {
                private boolean closed = false;

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    if (closed)
                        return;
                    closed = true;
                    super.close();
                    moveAtomically(staging, destination);
                    restrictOwnerReadWrite(destination);
                }
            };
        } catch (IOException e) {
            throw new DataStoreException("Failed to prepare EML plugin upload destination", e);
        }
    }

    @Override
    public boolean handleFile(String filename) {
        String safeName = sanitizeOrNull(filename);
        if (safeName == null)
            return false;
        Path archive = storageDir.resolve(safeName);
        if (!Files.isRegularFile(archive))
            return false;

        String previousActive = readActiveMarker();
        try {
            EmlRuntimeRegistry.forHub(hub).installFromArchive(safeName, archive);
            writeActiveMarker(safeName);
            if (oscarConfig != null) {
                oscarConfig.emlPluginPath = safeName;
            }
            pruneSupersededArchives(safeName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to load EML plugin archive '{}': {}", safeName, e.getMessage(), e);
            if (!safeName.equals(previousActive)) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException ignored) {
                }
            }
            return false;
        }
    }

    private String readActiveMarker() {
        Path marker = storageDir.resolve(ACTIVE_MARKER_FILE);
        if (!Files.isRegularFile(marker))
            return null;
        try {
            return sanitizeOrNull(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return null;
        }
    }

    private void writeActiveMarker(String activeFileName) {
        Path marker = storageDir.resolve(ACTIVE_MARKER_FILE);
        Path tmp = storageDir.resolve(ACTIVE_MARKER_FILE + ".tmp");
        try {
            Files.writeString(tmp, activeFileName + "\n", StandardCharsets.UTF_8);
            restrictOwnerReadWrite(tmp);
            moveAtomically(tmp, marker);
            restrictOwnerReadWrite(marker);
        } catch (IOException e) {
            logger.warn("Unable to update EML active plugin marker: {}", e.getMessage());
        }
    }

    private void pruneSupersededArchives(String keepFileName) {
        try (Stream<Path> list = Files.list(storageDir)) {
            list.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.equals(keepFileName)
                        && !name.equals(ACTIVE_MARKER_FILE)
                        && !name.endsWith(".uploading")
                        && EmlArchiveExtractor.isSupportedArchiveName(name)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictOwnerReadWrite(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    public static String sanitizeOrNull(String rawName) {
        if (rawName == null)
            return null;
        String trimmed = rawName.trim().replace('\\', '/');
        if (trimmed.contains(".."))
            return null;
        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0)
            trimmed = trimmed.substring(slash + 1);
        if (trimmed.isEmpty() || trimmed.startsWith(".") || !trimmed.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,200}"))
            return null;
        return trimmed;
    }
}
