/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.uff;

import com.botts.api.service.bucket.IBucketStore;
import com.botts.impl.service.bucket.util.UploadPolicy;
import com.botts.impl.service.oscar.Constants;
import com.botts.impl.service.oscar.uff.UffSmbConnector.ParsedSmbEndpoint;
import com.botts.impl.service.oscar.uff.UffSmbConnector.RemoteUffFileEntry;
import com.botts.impl.service.oscar.uff.UffSmbConnector.SmbSessionHandle;
import com.botts.impl.service.oscar.uff.WcoUffModels.UffCaseMetadata;
import com.botts.impl.service.oscar.uff.WcoUffModels.UffSearchMatch;
import com.botts.impl.service.oscar.vehicle.VehicleIncidentAggregator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.sensorhub.impl.utils.rad.eml.EmlArchiveExtractor;
import org.slf4j.Logger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Upgrade-safe runtime manager for WCO {@code UFF 2.0} &amp; {@code UFF 3.0.0} Cargo NII
 * integration.
 *
 * <ul>
 *   <li><b>Default-Off &amp; Zero Idle Overhead:</b> Ships disabled ({@code enabled = false}) with
 *       zero background threads, zero open sockets, and zero memory footprint unless explicitly
 *       enabled by an administrator.</li>
 *   <li><b>Incremental {@code O(delta)} Indexing:</b> Caches parsed XML metadata by
 *       {@code relativePath + sizeBytes + mtimeMillis} so unchanged {@code .uff} archives are never
 *       re-read across SMB refreshes or container restarts.</li>
 *   <li><b>Encrypted At-Rest SMB Credentials:</b> Encrypts read-only SMB passwords with AES-256-GCM
 *       and never returns plaintext secrets over status APIs or diagnostic bundles.</li>
 *   <li><b>Bucket Inspection Without SMB:</b> Supports inspecting and streaming entries from
 *       {@code .uff} files uploaded directly via {@code Upload Files} into {@code buckets/adjudication}
 *       even when automated SMB share integration is disabled.</li>
 * </ul>
 */
public final class CargoUffManager {

    private static final String CONFIG_FILE_NAME = "uff-config.json";
    private static final String INDEX_CACHE_FILE_NAME = "index-cache.json";
    private static final String SECRET_KEY_FILE_NAME = ".uff-secret.key";

    private static final long INDEX_FRESHNESS_TTL_MS = 15_000L;
    private static final long MAX_STREAM_ENTRY_BYTES = 200L * 1024L * 1024L;
    private static final int MAX_EXTRACT_N42_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EXTRACT_MANIFEST_BYTES = 32 * 1024 * 1024;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final SecureRandom RNG = new SecureRandom();

    public static final class PersistedUffConfig {
        public boolean enabled = false;
        public String sourceType = "smb"; // "smb" | "local"
        public String shareUrl = "";      // e.g. \\192.168.0.60\oscar-uff or smb://192.168.0.60/oscar-uff
        public String subPath = "";       // optional subfolder e.g. smb-date-archive
        public String localPath = "";     // used when sourceType == "local"
        public String domain = "";
        public String username = "";
        public String encryptedPassword = "";
        public int correlationWindowMinutes = 60;
        public boolean allowFuzzyOcrMatch = true;
        public String updatedAt = "";
    }

    private record CachedEntryRecord(
            String relativePath,
            long sizeBytes,
            long mtimeMillis,
            UffCaseMetadata metadata
    ) {}

    private final Path uffDir;
    private final Logger logger;
    private final java.util.function.Supplier<IBucketStore> bucketStoreSupplier;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile PersistedUffConfig currentConfig = new PersistedUffConfig();
    private final Map<String, CachedEntryRecord> cacheByRelPath = new ConcurrentHashMap<>();
    private volatile Map<String, UffCaseMetadata> indexByUuid = Map.of();
    private volatile long lastRefreshCompletedMs = 0L;
    private volatile long lastRefreshDurationMs = 0L;
    private volatile String lastError = "";
    private volatile String lastRefreshAtIso = "";

    public CargoUffManager(
            Path uffDir,
            Logger logger,
            java.util.function.Supplier<IBucketStore> bucketStoreSupplier
    ) {
        this.uffDir = Objects.requireNonNull(uffDir, "uffDir").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
        this.bucketStoreSupplier = bucketStoreSupplier;
        try {
            EmlArchiveExtractor.ensurePrivateDirectory(this.uffDir);
        } catch (IOException e) {
            logger.warn("Unable to initialize Cargo UFF extension directory {}: {}", this.uffDir, e.getMessage());
        }
    }

    public void restoreOnStartup() {
        Path cfgFile = uffDir.resolve(CONFIG_FILE_NAME);
        if (Files.isRegularFile(cfgFile)) {
            try {
                String json = Files.readString(cfgFile, StandardCharsets.UTF_8);
                PersistedUffConfig loaded = GSON.fromJson(json, PersistedUffConfig.class);
                if (loaded != null) {
                    this.currentConfig = sanitizeConfigFields(loaded);
                }
            } catch (Exception e) {
                logger.warn("Failed loading persisted Cargo UFF configuration from {}: {}", cfgFile, e.getMessage());
            }
        }
        loadIndexCacheFromDisk();
    }

    public Map<String, Object> getPublicActiveInfo() {
        PersistedUffConfig cfg = this.currentConfig;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.enabled);
        if (!cfg.enabled) {
            return out;
        }
        out.put("sourceType", cfg.sourceType);
        out.put("correlationWindowMinutes", cfg.correlationWindowMinutes);
        out.put("allowFuzzyOcrMatch", cfg.allowFuzzyOcrMatch);
        out.put("indexedCount", indexByUuid.size());
        out.put("scanners", collectScannerIds(indexByUuid.values()));
        out.put("lastRefreshAt", lastRefreshAtIso);
        out.put("lastError", lastError);
        return out;
    }

    public Map<String, Object> getAdminStatus() {
        PersistedUffConfig cfg = this.currentConfig;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.enabled);
        out.put("customActive", cfg.enabled);
        out.put("activeSource", cfg.enabled ? "custom" : "disabled");
        out.put("sourceType", cfg.sourceType);
        out.put("shareUrl", cfg.shareUrl);
        out.put("subPath", cfg.subPath);
        out.put("localPath", cfg.localPath);
        out.put("domain", cfg.domain);
        out.put("username", cfg.username);
        out.put("passwordConfigured", cfg.encryptedPassword != null && !cfg.encryptedPassword.isEmpty());
        out.put("correlationWindowMinutes", cfg.correlationWindowMinutes);
        out.put("allowFuzzyOcrMatch", cfg.allowFuzzyOcrMatch);
        out.put("updatedAt", cfg.updatedAt);

        var allCases = indexByUuid.values();
        long uff2 = allCases.stream().filter(c -> c.uffVersion().startsWith("2")).count();
        long uff3 = allCases.stream().filter(c -> c.uffVersion().startsWith("3")).count();
        out.put("indexedCount", allCases.size());
        out.put("uff2Count", uff2);
        out.put("uff3Count", uff3);
        out.put("scanners", collectScannerIds(allCases));
        out.put("lastRefreshAt", lastRefreshAtIso);
        out.put("lastRefreshDurationMs", lastRefreshDurationMs);
        out.put("lastError", lastError);
        return out;
    }

    public synchronized Map<String, Object> updateConfig(
            boolean enabled,
            String sourceType,
            String shareUrl,
            String subPath,
            String localPath,
            String domain,
            String username,
            String newPasswordOrNull,
            boolean clearPassword,
            Integer correlationWindowMinutes,
            Boolean allowFuzzyOcrMatch
    ) throws Exception {
        PersistedUffConfig next = new PersistedUffConfig();
        next.enabled = enabled;
        next.sourceType = "local".equalsIgnoreCase(sourceType) ? "local" : "smb";
        next.shareUrl = shareUrl != null ? shareUrl.trim() : "";
        next.subPath = UffSmbConnector.sanitizeRelativeSubPath(subPath);
        next.localPath = localPath != null ? localPath.trim() : "";
        next.domain = domain != null ? domain.trim() : "";
        next.username = username != null ? username.trim() : "";

        if (clearPassword) {
            next.encryptedPassword = "";
        } else if (newPasswordOrNull != null && !newPasswordOrNull.isEmpty()) {
            next.encryptedPassword = encryptSecret(newPasswordOrNull);
        } else {
            next.encryptedPassword = this.currentConfig.encryptedPassword;
        }

        int win = correlationWindowMinutes != null ? correlationWindowMinutes : this.currentConfig.correlationWindowMinutes;
        next.correlationWindowMinutes = Math.max(5, Math.min(10_080, win));
        next.allowFuzzyOcrMatch = allowFuzzyOcrMatch != null ? allowFuzzyOcrMatch : this.currentConfig.allowFuzzyOcrMatch;
        next.updatedAt = Instant.now().toString();

        if (next.enabled) {
            if ("local".equals(next.sourceType)) {
                UffSmbConnector.validateSafeLocalRoot(next.localPath);
            } else {
                UffSmbConnector.parseSmbTarget(next.shareUrl, next.subPath);
            }
        }

        saveConfigToDisk(next);
        this.currentConfig = next;
        this.lastRefreshCompletedMs = 0L;

        if (next.enabled) {
            refreshIndex(true);
        } else {
            this.lastError = "";
        }
        return getAdminStatus();
    }

    public synchronized Map<String, Object> resetToDisabled() throws IOException {
        Files.deleteIfExists(uffDir.resolve(CONFIG_FILE_NAME));
        Files.deleteIfExists(uffDir.resolve(INDEX_CACHE_FILE_NAME));
        this.currentConfig = new PersistedUffConfig();
        this.cacheByRelPath.clear();
        this.indexByUuid = Map.of();
        this.lastRefreshCompletedMs = 0L;
        this.lastRefreshDurationMs = 0L;
        this.lastRefreshAtIso = "";
        this.lastError = "";
        return getAdminStatus();
    }

    /**
     * Tests connectivity against candidate (or saved) SMB/local settings without mutating
     * saved configuration unless requested, returning diagnostic latency and archive counts.
     */
    public Map<String, Object> testConnection(
            String sourceType,
            String shareUrl,
            String subPath,
            String localPath,
            String domain,
            String username,
            String candidatePasswordOrNull
    ) throws Exception {
        long t0 = System.nanoTime();
        String effType = (sourceType != null && !sourceType.isBlank())
                ? ("local".equalsIgnoreCase(sourceType) ? "local" : "smb")
                : this.currentConfig.sourceType;
        String effShareUrl = (shareUrl != null && !shareUrl.isBlank()) ? shareUrl.trim() : this.currentConfig.shareUrl;
        String effSubPath = subPath != null ? UffSmbConnector.sanitizeRelativeSubPath(subPath) : this.currentConfig.subPath;
        String effLocalPath = (localPath != null && !localPath.isBlank()) ? localPath.trim() : this.currentConfig.localPath;
        String effDomain = domain != null ? domain.trim() : this.currentConfig.domain;
        String effUser = username != null ? username.trim() : this.currentConfig.username;
        String effPass;
        if (candidatePasswordOrNull != null && !candidatePasswordOrNull.isEmpty()) {
            effPass = candidatePasswordOrNull;
        } else {
            effPass = decryptSecret(this.currentConfig.encryptedPassword);
        }

        List<RemoteUffFileEntry> discovered;
        String targetDisplay;
        UffCaseMetadata sampleParsed = null;

        if ("local".equals(effType)) {
            Path root = UffSmbConnector.validateSafeLocalRoot(effLocalPath);
            targetDisplay = root.toString();
            discovered = UffSmbConnector.listLocalUffFiles(root);
            if (!discovered.isEmpty()) {
                RemoteUffFileEntry first = discovered.get(0);
                Path f = UffSmbConnector.resolveContainedLocalUffFile(root, first.relativePath());
                try (UffSeekableSource src = UffSeekableSource.fromLocalPath(f)) {
                    sampleParsed = WcoUffParser.parseArchiveMetadata(
                            UffZipReader.open(src), first.fileName(), first.relativePath(), first.sizeBytes(), first.mtimeMillis());
                }
            }
        } else {
            ParsedSmbEndpoint ep = UffSmbConnector.parseSmbTarget(effShareUrl, effSubPath);
            targetDisplay = ep.displayShareUrl();
            try (SmbSessionHandle handle = UffSmbConnector.connectSmb(ep, effDomain, effUser, effPass)) {
                discovered = UffSmbConnector.listSmbUffFiles(handle);
                if (!discovered.isEmpty()) {
                    RemoteUffFileEntry first = discovered.get(0);
                    try (UffSeekableSource src = UffSmbConnector.openSmbUffFile(handle, first.relativePath(), first.sizeBytes(), false)) {
                        sampleParsed = WcoUffParser.parseArchiveMetadata(
                                UffZipReader.open(src), first.fileName(), first.relativePath(), first.sizeBytes(), first.mtimeMillis());
                    }
                }
            }
        }

        long elapsedMs = TimeUnitNanosToMillis(System.nanoTime() - t0);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("sourceType", effType);
        res.put("target", targetDisplay);
        res.put("discoveredUffCount", discovered.size());
        res.put("latencyMs", elapsedMs);
        if (sampleParsed != null) {
            res.put("sampleUffUuid", sampleParsed.uffUuid());
            res.put("sampleUffVersion", sampleParsed.uffVersion());
            res.put("sampleScannerId", sampleParsed.scannerId());
        }
        return res;
    }

    public void refreshIndex(boolean force) throws IOException {
        PersistedUffConfig cfg = this.currentConfig;
        if (!cfg.enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && (now - lastRefreshCompletedMs) < INDEX_FRESHNESS_TTL_MS && !indexByUuid.isEmpty()) {
            return;
        }
        if (!force && !refreshLock.tryLock()) {
            // Another request is already refreshing; return existing index without stalling caller
            return;
        }
        if (force) {
            refreshLock.lock();
        }
        long t0 = System.nanoTime();
        try {
            if (!force && (System.currentTimeMillis() - lastRefreshCompletedMs) < INDEX_FRESHNESS_TTL_MS && !indexByUuid.isEmpty()) {
                return;
            }
            Map<String, UffCaseMetadata> nextByUuid = new LinkedHashMap<>();
            Set<String> liveRelPaths = new LinkedHashSet<>();
            boolean cacheDirty = false;

            if ("local".equals(cfg.sourceType)) {
                Path root = UffSmbConnector.validateSafeLocalRoot(cfg.localPath);
                List<RemoteUffFileEntry> entries = UffSmbConnector.listLocalUffFiles(root);
                for (RemoteUffFileEntry entry : entries) {
                    liveRelPaths.add(entry.relativePath());
                    CachedEntryRecord existing = cacheByRelPath.get(entry.relativePath());
                    if (existing != null
                            && existing.sizeBytes() == entry.sizeBytes()
                            && existing.mtimeMillis() == entry.mtimeMillis()
                            && existing.metadata() != null) {
                        nextByUuid.put(existing.metadata().uffUuid(), existing.metadata());
                        continue;
                    }
                    try {
                        Path file = UffSmbConnector.resolveContainedLocalUffFile(root, entry.relativePath());
                        try (UffSeekableSource src = UffSeekableSource.fromLocalPath(file)) {
                            UffCaseMetadata parsed = WcoUffParser.parseArchiveMetadata(
                                    UffZipReader.open(src),
                                    entry.fileName(),
                                    entry.relativePath(),
                                    entry.sizeBytes(),
                                    entry.mtimeMillis()
                            );
                            cacheByRelPath.put(entry.relativePath(),
                                    new CachedEntryRecord(entry.relativePath(), entry.sizeBytes(), entry.mtimeMillis(), parsed));
                            nextByUuid.put(parsed.uffUuid(), parsed);
                            cacheDirty = true;
                        }
                    } catch (Exception ex) {
                        logger.warn("Skipping malformed local UFF file '{}': {}", entry.relativePath(), ex.getMessage());
                    }
                }
            } else {
                ParsedSmbEndpoint ep = UffSmbConnector.parseSmbTarget(cfg.shareUrl, cfg.subPath);
                String pass = decryptSecret(cfg.encryptedPassword);
                try (SmbSessionHandle handle = UffSmbConnector.connectSmb(ep, cfg.domain, cfg.username, pass)) {
                    List<RemoteUffFileEntry> entries = UffSmbConnector.listSmbUffFiles(handle);
                    for (RemoteUffFileEntry entry : entries) {
                        liveRelPaths.add(entry.relativePath());
                        CachedEntryRecord existing = cacheByRelPath.get(entry.relativePath());
                        if (existing != null
                                && existing.sizeBytes() == entry.sizeBytes()
                                && existing.mtimeMillis() == entry.mtimeMillis()
                                && existing.metadata() != null) {
                            nextByUuid.put(existing.metadata().uffUuid(), existing.metadata());
                            continue;
                        }
                        try (UffSeekableSource src = UffSmbConnector.openSmbUffFile(
                                handle, entry.relativePath(), entry.sizeBytes(), false)) {
                            UffCaseMetadata parsed = WcoUffParser.parseArchiveMetadata(
                                    UffZipReader.open(src),
                                    entry.fileName(),
                                    entry.relativePath(),
                                    entry.sizeBytes(),
                                    entry.mtimeMillis()
                            );
                            cacheByRelPath.put(entry.relativePath(),
                                    new CachedEntryRecord(entry.relativePath(), entry.sizeBytes(), entry.mtimeMillis(), parsed));
                            nextByUuid.put(parsed.uffUuid(), parsed);
                            cacheDirty = true;
                        } catch (Exception ex) {
                            logger.warn("Skipping unreadable SMB UFF file '{}': {}", entry.relativePath(), ex.getMessage());
                        }
                    }
                }
            }

            if (cacheByRelPath.keySet().retainAll(liveRelPaths)) {
                cacheDirty = true;
            }
            this.indexByUuid = Map.copyOf(nextByUuid);
            this.lastRefreshCompletedMs = System.currentTimeMillis();
            this.lastRefreshDurationMs = TimeUnitNanosToMillis(System.nanoTime() - t0);
            this.lastRefreshAtIso = Instant.ofEpochMilli(this.lastRefreshCompletedMs).toString();
            this.lastError = "";

            if (cacheDirty) {
                saveIndexCacheToDisk();
            }
        } catch (Exception e) {
            this.lastError = e.getMessage() != null ? e.getMessage() : "Failed refreshing Cargo UFF share";
            throw (e instanceof IOException ioe) ? ioe : new IOException(this.lastError, e);
        } finally {
            refreshLock.unlock();
        }
    }

    public Map<String, Object> search(
            String uffIdOrPath,
            String vehicleId,
            String scannerId,
            String nearTimeIso,
            Integer windowMinutesOverride,
            Boolean allowFuzzyOverride,
            Integer limitOverride
    ) {
        PersistedUffConfig cfg = this.currentConfig;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", cfg.enabled);
        if (!cfg.enabled) {
            resp.put("matches", List.of());
            resp.put("scanners", List.of());
            return resp;
        }

        try {
            refreshIndex(false);
        } catch (IOException e) {
            logger.warn("Cargo UFF incremental refresh reported warning (serving cached index): {}", e.getMessage());
        }

        int limit = limitOverride != null ? Math.max(1, Math.min(200, limitOverride)) : 60;
        boolean fuzzy = allowFuzzyOverride != null ? allowFuzzyOverride : cfg.allowFuzzyOcrMatch;
        int windowMin = windowMinutesOverride != null ? windowMinutesOverride : cfg.correlationWindowMinutes;
        Long refEpochMs = parseInstantOrNull(nearTimeIso);

        List<UffSearchMatch> matches = new ArrayList<>();
        String extractedUuid = WcoUffParser.extractUffUuidFromInput(uffIdOrPath);

        if (!extractedUuid.isEmpty()) {
            UffCaseMetadata exact = indexByUuid.get(extractedUuid);
            if (exact != null) {
                matches.add(toSearchMatch(exact, false, exact.uffUuid(), refEpochMs));
            } else {
                for (UffCaseMetadata c : indexByUuid.values()) {
                    if (c.uffUuid().contains(extractedUuid)
                            || c.archiveFileName().toLowerCase(Locale.ROOT).contains(extractedUuid)) {
                        matches.add(toSearchMatch(c, false, c.uffUuid(), refEpochMs));
                    }
                }
            }
        } else {
            String normVehicle = VehicleIncidentAggregator.normalizeVehicleId(vehicleId != null ? vehicleId : "");
            String scannerFilter = scannerId != null ? scannerId.trim() : "";

            for (UffCaseMetadata c : indexByUuid.values()) {
                if (!scannerFilter.isEmpty() && !scannerFilter.equalsIgnoreCase(c.scannerId())) {
                    continue;
                }
                if (refEpochMs != null && windowMin > 0) {
                    long deltaMs = Math.abs(c.scanEpochMillis() - refEpochMs);
                    if (deltaMs > windowMin * 60_000L) {
                        continue;
                    }
                }

                boolean isFuzzy = false;
                String matchedId = "";
                if (!normVehicle.isEmpty()) {
                    boolean matched = false;
                    for (int i = 0; i < c.allNormalizedVehicleIds().size(); i++) {
                        String candNorm = c.allNormalizedVehicleIds().get(i);
                        String candDisp = i < c.allDisplayVehicleIds().size() ? c.allDisplayVehicleIds().get(i) : candNorm;
                        if (candNorm.equals(normVehicle) || candNorm.contains(normVehicle)) {
                            matched = true;
                            matchedId = candDisp;
                            isFuzzy = false;
                            break;
                        }
                    }
                    if (!matched && fuzzy && normVehicle.length() >= 6) {
                        for (int i = 0; i < c.allNormalizedVehicleIds().size(); i++) {
                            String candNorm = c.allNormalizedVehicleIds().get(i);
                            String candDisp = i < c.allDisplayVehicleIds().size() ? c.allDisplayVehicleIds().get(i) : candNorm;
                            if (WcoUffParser.isSingleCharOcrFuzzyMatch(normVehicle, candNorm)) {
                                matched = true;
                                matchedId = candDisp;
                                isFuzzy = true;
                                break;
                            }
                        }
                    }
                    if (!matched) {
                        continue;
                    }
                }

                matches.add(toSearchMatch(c, isFuzzy, matchedId, refEpochMs));
            }

            if (refEpochMs != null) {
                matches.sort(Comparator
                        .comparing((UffSearchMatch m) -> m.fuzzyMatch() ? 1 : 0)
                        .thenComparingLong(m -> m.timeDeltaSeconds() != null ? Math.abs(m.timeDeltaSeconds()) : Long.MAX_VALUE));
            } else {
                matches.sort(Comparator
                        .comparing((UffSearchMatch m) -> m.fuzzyMatch() ? 1 : 0)
                        .thenComparing(Comparator.comparingLong(UffSearchMatch::scanEpochMillis).reversed()));
            }
        }

        if (matches.size() > limit) {
            matches = matches.subList(0, limit);
        }

        resp.put("matches", matches);
        resp.put("totalIndexed", indexByUuid.size());
        resp.put("scanners", collectScannerIds(indexByUuid.values()));
        resp.put("lastError", lastError);
        return resp;
    }

    public UffCaseMetadata getCaseMetadata(String rawUffUuid, String bucketKey) throws IOException {
        if (bucketKey != null && !bucketKey.isBlank()) {
            return inspectBucketUff(bucketKey);
        }
        String uuid = WcoUffParser.extractUffUuidFromInput(rawUffUuid);
        if (uuid.isEmpty() && rawUffUuid != null) {
            uuid = rawUffUuid.trim().toLowerCase(Locale.ROOT);
        }
        if (uuid.isEmpty()) {
            throw new IllegalArgumentException("Missing UFF UUID or bucketKey");
        }
        UffCaseMetadata meta = indexByUuid.get(uuid);
        if (meta == null && currentConfig.enabled) {
            refreshIndex(false);
            meta = indexByUuid.get(uuid);
        }
        if (meta == null) {
            throw new IOException("Cargo UFF package not found: " + uuid);
        }
        return meta;
    }

    public void streamEntryToResponse(
            String rawUffUuid,
            String bucketKey,
            String entryName,
            HttpServletRequest req,
            HttpServletResponse resp
    ) throws IOException {
        String cleanEntry = UffZipReader.sanitizeEntryName(entryName);
        if (cleanEntry.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing UFF entry parameter");
            return;
        }

        try (UffSeekableSource src = openArchiveSource(rawUffUuid, bucketKey)) {
            UffZipReader zip = UffZipReader.open(src);
            UffZipReader.ZipEntryMeta entryMeta = zip.getEntry(cleanEntry);
            if (entryMeta == null || entryMeta.directory()) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "UFF archive entry not found: " + cleanEntry);
                return;
            }

            String etag = "\"" + Integer.toHexString(entryMeta.crc32()) + "-" + entryMeta.uncompressedSize() + "\"";
            String inm = req.getHeader("If-None-Match");
            if (etag.equals(inm)) {
                resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                resp.setHeader("ETag", etag);
                return;
            }

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType(resolveContentType(cleanEntry));
            resp.setHeader("ETag", etag);
            resp.setHeader("Cache-Control", "private, max-age=3600");
            resp.setHeader("X-Content-Type-Options", "nosniff");
            if (entryMeta.uncompressedSize() >= 0 && entryMeta.uncompressedSize() <= Integer.MAX_VALUE) {
                resp.setContentLengthLong(entryMeta.uncompressedSize());
            }
            zip.streamEntry(entryMeta, resp.getOutputStream(), MAX_STREAM_ENTRY_BYTES);
            resp.flushBuffer();
        }
    }

    /**
     * Zero-download adjudication staging: creates a compact {@code .uffref.json} reference in
     * {@code buckets/adjudication} (plus any lightweight embedded {@code .n42} radiation spectrum
     * and {@code manifest.pdf}) directly on the server so operators do not have to download and
     * re-upload multi-megabyte {@code .uff} archives through the browser.
     */
    public Map<String, Object> stageForAdjudication(String rawUffUuid) throws Exception {
        IBucketStore store = bucketStoreSupplier != null ? bucketStoreSupplier.get() : null;
        if (store == null) {
            throw new IllegalStateException("OSCAR BucketStore is unavailable");
        }
        UffCaseMetadata meta = getCaseMetadata(rawUffUuid, null);
        String bucket = Constants.ADJUDICATION_BUCKET;
        if (!store.bucketExists(bucket)) {
            store.createBucket(bucket);
        }

        String refKey = "uff-link-" + meta.uffUuid() + ".uffref.json";
        Map<String, Object> refPayload = new LinkedHashMap<>();
        refPayload.put("schema", "oscar-cargo-uff-reference-v1");
        refPayload.put("uffUuid", meta.uffUuid());
        refPayload.put("uffVersion", meta.uffVersion());
        refPayload.put("archiveFileName", meta.archiveFileName());
        refPayload.put("relativePath", meta.relativePath());
        refPayload.put("scannerId", meta.scannerId());
        refPayload.put("scanTimestamp", meta.scanTimestamp());
        refPayload.put("containerNum", meta.containerNum());
        refPayload.put("frontPlateNum", meta.frontPlateNum());
        refPayload.put("displayVehicleIds", meta.allDisplayVehicleIds());
        if (meta.declaration() != null) {
            refPayload.put("hsCode", meta.declaration().hsCode());
            refPayload.put("goodsDescription", meta.declaration().goodsDescription());
        }
        refPayload.put("linkedAt", Instant.now().toString());

        byte[] refBytes = GSON.toJson(refPayload).getBytes(StandardCharsets.UTF_8);
        Map<String, String> jsonMeta = Map.of("Content-Type", "application/json");
        UploadPolicy.validateUpload(refKey, jsonMeta);
        store.putObject(bucket, refKey, new ByteArrayInputStream(refBytes), jsonMeta);
        String refUri = store.getRelativeResourceURI(bucket, refKey);

        List<String> allStagedUris = new ArrayList<>();
        allStagedUris.add(refUri);

        String manifestUri = "";
        String n42Uri = "";
        String n42FileName = "";
        String n42ContentBase64 = "";

        try (UffSeekableSource src = openArchiveSource(meta.uffUuid(), null)) {
            UffZipReader zip = UffZipReader.open(src);

            if (!meta.manifestEntryPath().isEmpty()) {
                byte[] pdfBytes = zip.readEntryBytesBounded(meta.manifestEntryPath(), MAX_EXTRACT_MANIFEST_BYTES);
                String pdfKey = "uff-manifest-" + meta.uffUuid() + ".pdf";
                Map<String, String> pdfMeta = Map.of("Content-Type", "application/pdf");
                UploadPolicy.validateUpload(pdfKey, pdfMeta);
                store.putObject(bucket, pdfKey, new ByteArrayInputStream(pdfBytes), pdfMeta);
                manifestUri = store.getRelativeResourceURI(bucket, pdfKey);
                allStagedUris.add(manifestUri);
            }

            if (!meta.radiationFiles().isEmpty()) {
                var rad = meta.radiationFiles().get(0);
                byte[] n42Bytes = zip.readEntryBytesBounded(rad.entryPath(), MAX_EXTRACT_N42_BYTES);
                n42FileName = "uff-spectrum-" + meta.uffUuid() + ".n42";
                n42ContentBase64 = Base64.getEncoder().encodeToString(n42Bytes);
                Map<String, String> xmlMeta = Map.of("Content-Type", "application/xml");
                UploadPolicy.validateUpload(n42FileName, xmlMeta);
                store.putObject(bucket, n42FileName, new ByteArrayInputStream(n42Bytes), xmlMeta);
                n42Uri = store.getRelativeResourceURI(bucket, n42FileName);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("uffUuid", meta.uffUuid());
        out.put("referenceKey", refKey);
        out.put("referenceUri", refUri);
        out.put("manifestUri", manifestUri);
        out.put("n42Uri", n42Uri);
        out.put("n42FileName", n42FileName);
        out.put("n42ContentBase64", n42ContentBase64);
        out.put("stagedUris", allStagedUris);
        out.put("caseMetadata", meta);
        return out;
    }

    private UffCaseMetadata inspectBucketUff(String rawBucketKey) throws IOException {
        Path file = resolveAdjudicationBucketFile(rawBucketKey);
        if (rawBucketKey.toLowerCase(Locale.ROOT).endsWith(".uffref.json")) {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> ref = GSON.fromJson(json, Map.class);
            Object uuidObj = ref != null ? ref.get("uffUuid") : null;
            if (uuidObj instanceof String u && !u.isBlank()) {
                return getCaseMetadata(u, null);
            }
            throw new IOException("Invalid .uffref.json descriptor: " + rawBucketKey);
        }
        try (UffSeekableSource src = UffSeekableSource.fromLocalPath(file)) {
            return WcoUffParser.parseArchiveMetadata(
                    UffZipReader.open(src),
                    file.getFileName().toString(),
                    rawBucketKey,
                    Files.size(file),
                    Files.getLastModifiedTime(file).toMillis()
            );
        }
    }

    private UffSeekableSource openArchiveSource(String rawUffUuid, String bucketKey) throws IOException {
        if (bucketKey != null && !bucketKey.isBlank()) {
            Path file = resolveAdjudicationBucketFile(bucketKey);
            if (bucketKey.toLowerCase(Locale.ROOT).endsWith(".uffref.json")) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, Object> ref = GSON.fromJson(json, Map.class);
                Object uuidObj = ref != null ? ref.get("uffUuid") : null;
                if (uuidObj instanceof String u && !u.isBlank()) {
                    return openArchiveSource(u, null);
                }
                throw new IOException("Invalid .uffref.json descriptor");
            }
            return UffSeekableSource.fromLocalPath(file);
        }

        UffCaseMetadata meta = getCaseMetadata(rawUffUuid, null);
        PersistedUffConfig cfg = this.currentConfig;
        if (!cfg.enabled) {
            throw new IOException("Cargo UFF share integration is disabled");
        }
        if ("local".equals(cfg.sourceType)) {
            Path root = UffSmbConnector.validateSafeLocalRoot(cfg.localPath);
            Path file = UffSmbConnector.resolveContainedLocalUffFile(root, meta.relativePath());
            return UffSeekableSource.fromLocalPath(file);
        }
        ParsedSmbEndpoint ep = UffSmbConnector.parseSmbTarget(cfg.shareUrl, cfg.subPath);
        String pass = decryptSecret(cfg.encryptedPassword);
        SmbSessionHandle handle = UffSmbConnector.connectSmb(ep, cfg.domain, cfg.username, pass);
        return UffSmbConnector.openSmbUffFile(handle, meta.relativePath(), meta.archiveSizeBytes(), true);
    }

    private Path resolveAdjudicationBucketFile(String rawKey) throws IOException {
        IBucketStore store = bucketStoreSupplier != null ? bucketStoreSupplier.get() : null;
        if (store == null) {
            throw new IOException("BucketStore is unavailable");
        }
        String norm = rawKey.replace('\\', '/').trim();
        while (norm.startsWith("/")) {
            norm = norm.substring(1);
        }
        String prefix = Constants.ADJUDICATION_BUCKET + "/";
        if (norm.startsWith(prefix)) {
            norm = norm.substring(prefix.length());
        }
        String safeKey = UffSmbConnector.sanitizeRelativeSubPath(norm);
        if (safeKey.isEmpty()) {
            throw new SecurityException("Invalid bucket key");
        }
        try {
            String rawResolved = store.getResourceURI(Constants.ADJUDICATION_BUCKET, safeKey);
            Path resolved = (rawResolved != null && !rawResolved.isBlank()) ? java.nio.file.Paths.get(rawResolved) : null;
            if (resolved == null || !Files.isRegularFile(resolved) || Files.isSymbolicLink(resolved)) {
                throw new IOException("Uploaded UFF file not found in adjudication bucket: " + safeKey);
            }
            return resolved;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("Unable to resolve adjudication bucket object '" + safeKey + "': " + e.getMessage(), e);
        }
    }

    private static UffSearchMatch toSearchMatch(
            UffCaseMetadata c,
            boolean fuzzyMatch,
            String matchedIdentifier,
            Long refEpochMs
    ) {
        boolean hasHemd = c.xrayFiles().stream().anyMatch(x -> x.hemdEntryPath() != null && !x.hemdEntryPath().isEmpty());
        Long deltaSec = refEpochMs != null ? (c.scanEpochMillis() - refEpochMs) / 1000L : null;
        return new UffSearchMatch(
                c.uffUuid(),
                c.uffVersion(),
                c.archiveFileName(),
                c.relativePath(),
                c.archiveSizeBytes(),
                c.scannerId(),
                c.scannerManufacturer(),
                c.scannerModel(),
                c.scanTimestamp(),
                c.scanEpochMillis(),
                c.containerNum(),
                c.frontPlateNum(),
                c.allDisplayVehicleIds(),
                c.declaration() != null ? c.declaration().hsCode() : "",
                c.declaration() != null ? c.declaration().goodsDescription() : "",
                c.containerWeightKg(),
                !c.previewEntryPath().isEmpty(),
                hasHemd,
                !c.manifestEntryPath().isEmpty(),
                !c.radiationFiles().isEmpty(),
                c.annotations().size(),
                c.analysisResult(),
                fuzzyMatch,
                matchedIdentifier,
                deltaSec
        );
    }

    private static List<String> collectScannerIds(java.util.Collection<UffCaseMetadata> cases) {
        Set<String> set = new java.util.TreeSet<>();
        for (UffCaseMetadata c : cases) {
            if (c.scannerId() != null && !c.scannerId().isBlank()) {
                set.add(c.scannerId());
            }
        }
        return List.copyOf(set);
    }

    private static String resolveContentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) return "image/tiff";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".xml") || lower.endsWith(".n42")) return "application/xml;charset=utf-8";
        return "application/octet-stream";
    }

    private static Long parseInstantOrNull(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso.trim()).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private static long TimeUnitNanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private static PersistedUffConfig sanitizeConfigFields(PersistedUffConfig c) {
        c.sourceType = "local".equalsIgnoreCase(c.sourceType) ? "local" : "smb";
        c.shareUrl = c.shareUrl != null ? c.shareUrl.trim() : "";
        c.subPath = UffSmbConnector.sanitizeRelativeSubPath(c.subPath);
        c.localPath = c.localPath != null ? c.localPath.trim() : "";
        c.domain = c.domain != null ? c.domain.trim() : "";
        c.username = c.username != null ? c.username.trim() : "";
        c.encryptedPassword = c.encryptedPassword != null ? c.encryptedPassword.trim() : "";
        c.correlationWindowMinutes = Math.max(5, Math.min(10_080, c.correlationWindowMinutes > 0 ? c.correlationWindowMinutes : 60));
        c.updatedAt = c.updatedAt != null ? c.updatedAt : "";
        return c;
    }

    private void saveConfigToDisk(PersistedUffConfig cfg) throws IOException {
        EmlArchiveExtractor.ensurePrivateDirectory(uffDir);
        writeAtomicallyPrivate(uffDir.resolve(CONFIG_FILE_NAME), GSON.toJson(cfg).getBytes(StandardCharsets.UTF_8));
    }

    private void loadIndexCacheFromDisk() {
        Path cacheFile = uffDir.resolve(INDEX_CACHE_FILE_NAME);
        if (!Files.isRegularFile(cacheFile)) {
            return;
        }
        try {
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<CachedEntryRecord>>() {}.getType();
            List<CachedEntryRecord> records = GSON.fromJson(json, listType);
            if (records == null) {
                return;
            }
            Map<String, UffCaseMetadata> byUuid = new HashMap<>();
            for (CachedEntryRecord rec : records) {
                if (rec != null && rec.relativePath() != null && rec.metadata() != null) {
                    cacheByRelPath.put(rec.relativePath(), rec);
                    byUuid.put(rec.metadata().uffUuid(), rec.metadata());
                }
            }
            this.indexByUuid = Map.copyOf(byUuid);
        } catch (Exception e) {
            logger.warn("Ignoring unreadable Cargo UFF index cache at {}: {}", cacheFile, e.getMessage());
        }
    }

    private void saveIndexCacheToDisk() {
        try {
            EmlArchiveExtractor.ensurePrivateDirectory(uffDir);
            List<CachedEntryRecord> snapshot = new ArrayList<>(cacheByRelPath.values());
            writeAtomicallyPrivate(uffDir.resolve(INDEX_CACHE_FILE_NAME), GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warn("Unable to persist Cargo UFF index cache: {}", e.getMessage());
        }
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        Path keyFile = uffDir.resolve(SECRET_KEY_FILE_NAME);
        if (Files.isRegularFile(keyFile)) {
            byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
            if (raw.length == 32) {
                return new SecretKeySpec(raw, "AES");
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, RNG);
        SecretKey key = kg.generateKey();
        String b64 = Base64.getEncoder().encodeToString(key.getEncoded());
        EmlArchiveExtractor.ensurePrivateDirectory(uffDir);
        writeAtomicallyPrivate(keyFile, (b64 + "\n").getBytes(StandardCharsets.UTF_8));
        return key;
    }

    private String encryptSecret(String plaintext) throws Exception {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        SecretKey key = getOrCreateSecretKey();
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return "v1:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
    }

    private String decryptSecret(String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        String[] parts = encoded.split(":", 3);
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            throw new IOException("Malformed encrypted UFF credential token");
        }
        try {
            SecretKey key = getOrCreateSecretKey();
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] ct = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("Unable to decrypt stored UFF share password: " + e.getMessage(), e);
        }
    }

    private static void writeAtomicallyPrivate(Path dest, byte[] bytes) throws IOException {
        Path tmp = dest.resolveSibling("." + dest.getFileName() + ".tmp." + System.nanoTime());
        Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(tmp, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        }
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
