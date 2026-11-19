/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.extensions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.sensorhub.impl.utils.rad.eml.EmlArchiveExtractor;
import org.slf4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Manages administrator-uploaded offline regional {@code .pmtiles} basemap archives.
 *
 * <p>Stores {@code .pmtiles} payloads in {@code /var/lib/oscar/extensions/maps-storage/} (persistent state
 * volume, preserved across upgrades and backed up incrementally) and active selection metadata in
 * {@code /var/lib/oscar/extensions/maps/.active.json}. Serves RFC 9110 {@code HTTP 206 Partial Content}
 * byte-range slices directly from {@link FileChannel} with zero full-file memory buffering.</p>
 */
public final class OfflineMapManager {

    private static final String ACTIVE_STATE_FILE = ".active.json";
    private static final long MAX_PMTILES_BYTES = 2_000L * 1024L * 1024L; // 2000 MiB (within Nginx 2g cap)
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public record StoredMapPack(
            String fileName,
            String displayName,
            long sizeBytes,
            String sha256,
            PmTilesHeader header,
            String uploadedAt
    ) {}

    private static final class PersistedMapState {
        String activeFileName;
        String displayName;
        boolean defaultBasemap = true;
        String sha256;
        String activatedAt;
    }

    private final Path stateDir;
    private final Path archiveMapsDir;
    private final Logger logger;
    private final Object lock = new Object();
    private volatile PersistedMapState activeState;
    private volatile PmTilesHeader activeHeader;
    private volatile long activeSizeBytes;
    private volatile String lastError = "";

    public OfflineMapManager(Path stateDir, Path archiveMapsDir, Logger logger) {
        this.stateDir = stateDir.toAbsolutePath().normalize();
        this.archiveMapsDir = archiveMapsDir.toAbsolutePath().normalize();
        this.logger = logger;
        try {
            EmlArchiveExtractor.ensurePrivateDirectory(this.stateDir);
            Files.createDirectories(this.archiveMapsDir);
        } catch (IOException e) {
            logger.warn("Unable to initialize offline maps directories ({}, {}): {}",
                    this.stateDir, this.archiveMapsDir, e.getMessage());
        }
    }

    public void restoreActiveOnStartup() {
        synchronized (lock) {
            Path marker = stateDir.resolve(ACTIVE_STATE_FILE);
            if (!Files.isRegularFile(marker)) {
                return;
            }
            try {
                PersistedMapState loaded = GSON.fromJson(
                        Files.readString(marker, StandardCharsets.UTF_8), PersistedMapState.class);
                if (loaded == null || loaded.activeFileName == null || loaded.activeFileName.isBlank()) {
                    return;
                }
                String safe = sanitizePmTilesName(loaded.activeFileName);
                if (safe == null) {
                    return;
                }
                Path file = archiveMapsDir.resolve(safe);
                if (!Files.isRegularFile(file)) {
                    logger.warn("Active offline PMTiles map '{}' was not found in {}; reverting to built-in basemap",
                            safe, archiveMapsDir);
                    return;
                }
                this.activeHeader = PmTilesHeader.readAndValidate(file);
                this.activeSizeBytes = Files.size(file);
                this.activeState = loaded;
                logger.info("Restored active offline PMTiles basemap '{}' (tileType={}, zoom={}..{}, default={})",
                        safe, activeHeader.tileType(), activeHeader.minZoom(), activeHeader.maxZoom(), loaded.defaultBasemap);
            } catch (Exception e) {
                logger.warn("Failed to restore active offline PMTiles map state: {}", e.getMessage());
            }
        }
    }

    public Map<String, Object> getAdminStatus(String servletRootPrefix) {
        synchronized (lock) {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean custom = activeState != null && activeHeader != null;
            out.put("activeSource", custom ? "custom" : "builtin");
            out.put("customActive", custom);
            out.put("builtinDefaultLabel", "OpenStreetMap Streets + Esri World Imagery");
            out.put("activeFileName", custom ? activeState.activeFileName : "");
            out.put("displayName", custom ? activeState.displayName : "OpenStreetMap Streets + Esri World Imagery");
            out.put("defaultBasemap", custom && activeState.defaultBasemap);
            out.put("sizeBytes", custom ? activeSizeBytes : 0L);
            out.put("sha256", custom && activeState.sha256 != null ? activeState.sha256 : "");
            out.put("activatedAt", custom && activeState.activatedAt != null ? activeState.activatedAt : "");
            out.put("header", custom ? headerToMap(activeHeader) : null);
            out.put("tileUrl", custom ? buildTileUrl(servletRootPrefix, activeState.activeFileName) : "");
            out.put("storedPacks", listStoredPackMaps());
            out.put("lastError", lastError);
            return out;
        }
    }

    private static Map<String, Object> headerToMap(PmTilesHeader h) {
        if (h == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("specVersion", h.specVersion());
        m.put("tileType", h.tileType());
        m.put("tileTypeCode", h.tileTypeCode());
        m.put("tileCompression", h.tileCompression());
        m.put("minZoom", h.minZoom());
        m.put("maxZoom", h.maxZoom());
        m.put("minLon", h.minLon());
        m.put("minLat", h.minLat());
        m.put("maxLon", h.maxLon());
        m.put("maxLat", h.maxLat());
        m.put("centerZoom", h.centerZoom());
        m.put("centerLon", h.centerLon());
        m.put("centerLat", h.centerLat());
        m.put("addressedTilesCount", h.addressedTilesCount());
        m.put("tileEntriesCount", h.tileEntriesCount());
        return m;
    }

    public Map<String, Object> getPublicActiveMapInfo(String servletRootPrefix) {
        synchronized (lock) {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean custom = activeState != null && activeHeader != null;
            out.put("active", custom);
            if (!custom) {
                return out;
            }
            out.put("fileName", activeState.activeFileName);
            out.put("displayName", activeState.displayName != null && !activeState.displayName.isBlank()
                    ? activeState.displayName
                    : stripExtension(activeState.activeFileName));
            out.put("defaultBasemap", activeState.defaultBasemap);
            out.put("sizeBytes", activeSizeBytes);
            out.put("tileUrl", buildTileUrl(servletRootPrefix, activeState.activeFileName));
            out.put("tileType", activeHeader.tileType());
            out.put("tileCompression", activeHeader.tileCompression());
            out.put("minZoom", activeHeader.minZoom());
            out.put("maxZoom", activeHeader.maxZoom());
            out.put("bounds", List.of(
                    List.of(activeHeader.minLat(), activeHeader.minLon()),
                    List.of(activeHeader.maxLat(), activeHeader.maxLon())
            ));
            out.put("center", Map.of(
                    "lat", activeHeader.centerLat(),
                    "lon", activeHeader.centerLon(),
                    "zoom", activeHeader.centerZoom()
            ));
            return out;
        }
    }

    public Map<String, Object> uploadAndActivate(
            String rawFileName,
            String displayName,
            boolean defaultBasemap,
            InputStream bodyStream,
            String servletRootPrefix
    ) throws Exception {
        String safeName = sanitizePmTilesName(rawFileName);
        if (safeName == null) {
            String msg = "Invalid PMTiles filename (must end in .pmtiles and contain only safe ASCII characters): " + rawFileName;
            lastError = msg;
            throw new IllegalArgumentException(msg);
        }
        Files.createDirectories(archiveMapsDir);
        Path staging = archiveMapsDir.resolve("." + safeName + ".uploading." + System.nanoTime());
        Path dest = archiveMapsDir.resolve(safeName);

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long written = 0L;
            byte[] buf = new byte[262_144];
            try (OutputStream out = Files.newOutputStream(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                int n;
                while ((n = bodyStream.read(buf)) >= 0) {
                    written += n;
                    if (written > MAX_PMTILES_BYTES) {
                        throw new IllegalArgumentException("PMTiles upload exceeds 2000 MiB limit");
                    }
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (written < PmTilesHeader.HEADER_SIZE_BYTES) {
                throw new IllegalArgumentException("Uploaded .pmtiles file is truncated (" + written + " bytes)");
            }
            PmTilesHeader header = PmTilesHeader.readAndValidate(staging);
            String sha256 = HexFormat.of().formatHex(md.digest());

            synchronized (lock) {
                moveAtomically(staging, dest);
                PersistedMapState st = new PersistedMapState();
                st.activeFileName = safeName;
                st.displayName = (displayName != null && !displayName.isBlank())
                        ? displayName.trim()
                        : stripExtension(safeName);
                st.defaultBasemap = defaultBasemap;
                st.sha256 = sha256;
                st.activatedAt = Instant.now().toString();
                saveStateLocked(st);
                this.activeState = st;
                this.activeHeader = header;
                this.activeSizeBytes = written;
                this.lastError = "";
            }
            logger.info("Uploaded and activated offline PMTiles basemap '{}' ({} bytes, type={}, zoom={}..{})",
                    safeName, written, header.tileType(), header.minZoom(), header.maxZoom());
            return getAdminStatus(servletRootPrefix);
        } catch (Exception e) {
            Files.deleteIfExists(staging);
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw e;
        }
    }

    public Map<String, Object> activateExisting(String rawFileName, Boolean defaultBasemap, String displayName, String servletRootPrefix) throws Exception {
        String safeName = sanitizePmTilesName(rawFileName);
        if (safeName == null) {
            throw new IllegalArgumentException("Invalid PMTiles file name: " + rawFileName);
        }
        synchronized (lock) {
            Path file = archiveMapsDir.resolve(safeName);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Stored PMTiles archive not found: " + safeName);
            }
            PmTilesHeader header = PmTilesHeader.readAndValidate(file);
            long sz = Files.size(file);
            String sha = sha256File(file);

            PersistedMapState st = new PersistedMapState();
            st.activeFileName = safeName;
            st.displayName = (displayName != null && !displayName.isBlank())
                    ? displayName.trim()
                    : (activeState != null && safeName.equals(activeState.activeFileName) && activeState.displayName != null
                    ? activeState.displayName
                    : stripExtension(safeName));
            st.defaultBasemap = defaultBasemap != null
                    ? defaultBasemap
                    : (activeState == null || activeState.defaultBasemap);
            st.sha256 = sha;
            st.activatedAt = Instant.now().toString();
            saveStateLocked(st);
            this.activeState = st;
            this.activeHeader = header;
            this.activeSizeBytes = sz;
            this.lastError = "";
            return getAdminStatus(servletRootPrefix);
        }
    }

    public Map<String, Object> setDefaultBasemap(boolean defaultBasemap, String servletRootPrefix) throws IOException {
        synchronized (lock) {
            if (activeState != null) {
                activeState.defaultBasemap = defaultBasemap;
                saveStateLocked(activeState);
            }
            return getAdminStatus(servletRootPrefix);
        }
    }

    public Map<String, Object> resetToDefault(String servletRootPrefix) throws IOException {
        synchronized (lock) {
            this.activeState = null;
            this.activeHeader = null;
            this.activeSizeBytes = 0L;
            this.lastError = "";
            Files.deleteIfExists(stateDir.resolve(ACTIVE_STATE_FILE));
            logger.info("Reset basemap configuration to built-in OpenStreetMap + Esri default (stored .pmtiles files preserved)");
            return getAdminStatus(servletRootPrefix);
        }
    }

    public Map<String, Object> deleteStoredPack(String rawFileName, String servletRootPrefix) throws IOException {
        String safeName = sanitizePmTilesName(rawFileName);
        if (safeName == null) {
            throw new IllegalArgumentException("Invalid PMTiles file name: " + rawFileName);
        }
        synchronized (lock) {
            if (activeState != null && safeName.equals(activeState.activeFileName)) {
                this.activeState = null;
                this.activeHeader = null;
                this.activeSizeBytes = 0L;
                Files.deleteIfExists(stateDir.resolve(ACTIVE_STATE_FILE));
            }
            Files.deleteIfExists(archiveMapsDir.resolve(safeName));
            this.lastError = "";
            return getAdminStatus(servletRootPrefix);
        }
    }

    public void servePmTilesBytes(String rawFileName, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String safeName = sanitizePmTilesName(rawFileName);
        if (safeName == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid PMTiles filename");
            return;
        }
        Path file = archiveMapsDir.resolve(safeName);
        if (!Files.isRegularFile(file)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "PMTiles archive not found");
            return;
        }

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            long mtime = Files.getLastModifiedTime(file).toMillis();
            String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(mtime) + "\"";

            resp.setContentType("application/vnd.pmtiles");
            resp.setHeader("Accept-Ranges", "bytes");
            resp.setHeader("ETag", etag);
            resp.setHeader("Cache-Control", "private, max-age=3600");
            resp.setHeader("X-Content-Type-Options", "nosniff");

            if ("HEAD".equalsIgnoreCase(req.getMethod())) {
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.setHeader("Content-Length", String.valueOf(size));
                return;
            }

            String rangeHeader = req.getHeader("Range");
            if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.setHeader("Content-Length", String.valueOf(size));
                streamRange(ch, 0L, size, resp.getOutputStream());
                return;
            }

            long start = 0L;
            long end = size - 1L;
            String spec = rangeHeader.substring("bytes=".length()).trim();
            int comma = spec.indexOf(',');
            if (comma >= 0) {
                spec = spec.substring(0, comma).trim();
            }
            String[] parts = spec.split("-", -1);
            try {
                if (parts[0].isEmpty()) {
                    long suffix = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : 0L;
                    start = Math.max(0L, size - Math.max(1L, suffix));
                } else {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    }
                }
            } catch (NumberFormatException bad) {
                resp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                resp.setHeader("Content-Range", "bytes */" + size);
                return;
            }

            if (size == 0L || start < 0L || start >= size || end < start) {
                resp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                resp.setHeader("Content-Range", "bytes */" + size);
                return;
            }
            if (end >= size) {
                end = size - 1L;
            }
            long length = end - start + 1L;
            resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            resp.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            resp.setHeader("Content-Length", String.valueOf(length));
            streamRange(ch, start, length, resp.getOutputStream());
        }
    }

    private static void streamRange(FileChannel ch, long offset, long length, OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(262_144);
        long pos = offset;
        long remaining = length;
        while (remaining > 0L) {
            buf.clear();
            if (remaining < buf.capacity()) {
                buf.limit((int) remaining);
            }
            int n = ch.read(buf, pos);
            if (n < 0) {
                break;
            }
            out.write(buf.array(), 0, n);
            pos += n;
            remaining -= n;
        }
    }

    private List<Map<String, Object>> listStoredPackMaps() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (StoredMapPack p : listStoredPacks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileName", p.fileName());
            m.put("displayName", p.displayName());
            m.put("sizeBytes", p.sizeBytes());
            m.put("sha256", p.sha256());
            m.put("header", headerToMap(p.header()));
            m.put("uploadedAt", p.uploadedAt());
            list.add(m);
        }
        return list;
    }

    private List<StoredMapPack> listStoredPacks() {
        if (!Files.isDirectory(archiveMapsDir)) {
            return List.of();
        }
        List<StoredMapPack> packs = new ArrayList<>();
        try (Stream<Path> s = Files.list(archiveMapsDir)) {
            List<Path> files = s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith(".") && n.toLowerCase(Locale.ROOT).endsWith(".pmtiles");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path p : files) {
                try {
                    PmTilesHeader hdr = PmTilesHeader.readAndValidate(p);
                    String fn = p.getFileName().toString();
                    String disp = (activeState != null && fn.equals(activeState.activeFileName) && activeState.displayName != null)
                            ? activeState.displayName
                            : stripExtension(fn);
                    String sha = (activeState != null && fn.equals(activeState.activeFileName) && activeState.sha256 != null)
                            ? activeState.sha256
                            : "";
                    packs.add(new StoredMapPack(
                            fn,
                            disp,
                            Files.size(p),
                            sha,
                            hdr,
                            Files.getLastModifiedTime(p).toInstant().toString()
                    ));
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return packs;
    }

    private void saveStateLocked(PersistedMapState st) throws IOException {
        EmlArchiveExtractor.ensurePrivateDirectory(stateDir);
        Path tmp = stateDir.resolve(ACTIVE_STATE_FILE + ".tmp");
        Path dst = stateDir.resolve(ACTIVE_STATE_FILE);
        Files.writeString(tmp, GSON.toJson(st) + "\n", StandardCharsets.UTF_8);
        moveAtomically(tmp, dst);
    }

    private static void moveAtomically(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String buildTileUrl(String servletRootPrefix, String fileName) {
        String prefix = (servletRootPrefix == null || servletRootPrefix.isBlank())
                ? "/sensorhub/oscar/extensions"
                : servletRootPrefix;
        return prefix + "/maps/tiles/" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public static String sanitizePmTilesName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().replace('\\', '/');
        if (trimmed.contains("..")) {
            return null;
        }
        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0) {
            trimmed = trimmed.substring(slash + 1);
        }
        if (trimmed.isEmpty() || trimmed.startsWith(".") || !trimmed.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.(?i:pmtiles)")) {
            return null;
        }
        return trimmed;
    }

    private static String sha256File(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[65_536];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
