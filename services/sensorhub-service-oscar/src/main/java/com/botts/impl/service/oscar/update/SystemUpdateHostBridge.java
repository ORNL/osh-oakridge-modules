/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.update;

import com.botts.impl.service.oscar.sandbox.TrainingSandboxEnvironment;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Schema-validated file-queue bridge between authenticated OSCAR Administrator
 * sessions ({@code /sensorhub/oscar/system-update/*}) and the detached host-side
 * {@code oscar-update} agent ({@code oscar-update.sh} on Ubuntu 24.04 LTS /
 * {@code oscar-update.ps1} on Windows 11) without exposing {@code docker.sock}
 * or elevated host privileges inside the unprivileged {@code oscar} container.
 */
public final class SystemUpdateHostBridge {

    public static final String PROP_CONTROL_DIR = "oscar.update.controlDir";
    public static final String PROP_CURRENT_VERSION = "oscar.update.currentVersion";
    public static final long MAX_UPLOAD_BYTES = 2_147_483_648L; // 2 GiB
    public static final double MIN_FREE_DISK_GIB = 4.0;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern SAFE_BUNDLE_NAME = Pattern.compile("^[A-Za-z0-9._-]+\\.zip$");
    private static final Pattern ENV_VERSION_LINE = Pattern.compile(
            "^OSCAR_VERSION=([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?)\\s*$");
    private static final Pattern CHANGELOG_HEADING = Pattern.compile(
            "^##\\s+([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?)\\b");

    private final Path controlDir;
    private final Path stagedDir;
    private final Logger log;

    public SystemUpdateHostBridge(Logger log) {
        this(resolveDefaultControlDir(), log);
    }

    public SystemUpdateHostBridge(Path controlDir, Logger log) {
        this.controlDir = controlDir.toAbsolutePath().normalize();
        this.stagedDir = this.controlDir.resolve("staged");
        this.log = log;
    }

    private static Path resolveDefaultControlDir() {
        String override = System.getProperty(PROP_CONTROL_DIR);
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        return Paths.get("/var/lib/oscar/.moduledata/update-control");
    }

    public Path getControlDir() {
        return controlDir;
    }

    public Path getStagedDir() {
        return stagedDir;
    }

    public String resolveCurrentVersion() {
        String prop = System.getProperty(PROP_CURRENT_VERSION);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String envVer = System.getenv("OSCAR_VERSION");
        if (envVer != null && !envVer.isBlank() && !"local".equalsIgnoreCase(envVer.trim())) {
            return envVer.trim();
        }
        for (String candidate : new String[]{"/opt/oscar/changelog.md", "changelog.md"}) {
            Path p = Paths.get(candidate);
            if (Files.isRegularFile(p)) {
                try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Matcher m = CHANGELOG_HEADING.matcher(line.trim());
                        if (m.find()) {
                            return m.group(1);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return "3.11.10";
    }

    public boolean isGuiUpdateEnabledByEnv() {
        String prop = System.getProperty("oscar.update.guiEnabled");
        if (prop != null && !prop.isBlank()) {
            return !"false".equalsIgnoreCase(prop.trim()) && !"disabled".equalsIgnoreCase(prop.trim());
        }
        String env = System.getenv("OSCAR_GUI_UPDATE");
        if (env != null && !env.isBlank()) {
            return !"false".equalsIgnoreCase(env.trim()) && !"disabled".equalsIgnoreCase(env.trim());
        }
        return true;
    }

    public JsonObject readStatus(JsonObject cachedLatestRelease) {
        String currentVersion = resolveCurrentVersion();
        boolean sandboxMode = TrainingSandboxEnvironment.isSandboxMode();
        boolean envEnabled = isGuiUpdateEnabledByEnv();

        JsonObject obj = readRawStatusFile();
        if (obj == null) {
            obj = new JsonObject();
            obj.addProperty("agentActive", false);
            obj.addProperty("state", "IDLE");
            obj.addProperty("stage", "IDLE");
            obj.addProperty("progressPercent", 0);
        } else if (!obj.has("agentActive")) {
            obj.addProperty("agentActive", true);
        }

        boolean hostBridgeEnabled = !obj.has("guiUpdateEnabled") || optBoolean(obj, "guiUpdateEnabled", true);
        obj.addProperty("currentVersion", currentVersion);
        obj.addProperty("sandboxMode", sandboxMode);
        obj.addProperty("guiUpdateEnabled", envEnabled && hostBridgeEnabled && !sandboxMode);
        obj.addProperty("requiresGitHubLogin", false);
        if (!obj.has("availableDiskGib")) {
            obj.addProperty("availableDiskGib", probeAvailableDiskGib());
        }
        appendCliInstructions(obj);

        JsonObject staged = describeLatestStagedBundle();
        if (staged != null) {
            obj.add("stagedBundle", staged);
        }
        if (cachedLatestRelease != null) {
            obj.add("latestRelease", cachedLatestRelease);
        }
        return obj;
    }

    private JsonObject readRawStatusFile() {
        Path statusFile = controlDir.resolve("status.json");
        if (!Files.isRegularFile(statusFile)) {
            return null;
        }
        try {
            String raw = Files.readString(statusFile, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("Failed reading system-update status.json: {}", e.getMessage());
            }
        }
        return null;
    }

    private double probeAvailableDiskGib() {
        try {
            Path existing = Files.exists(controlDir) ? controlDir : Paths.get("/").toAbsolutePath();
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing != null) {
                FileStore store = Files.getFileStore(existing);
                double gib = store.getUsableSpace() / (1024.0 * 1024.0 * 1024.0);
                return Math.round(gib * 10.0) / 10.0;
            }
        } catch (Exception ignored) {
        }
        return 32.0;
    }

    private JsonObject describeLatestStagedBundle() {
        if (!Files.isDirectory(stagedDir)) {
            return null;
        }
        try (var stream = Files.list(stagedDir)) {
            Path newest = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElse(null);
            if (newest == null) {
                return null;
            }
            JsonObject info = new JsonObject();
            String fileName = newest.getFileName().toString();
            info.addProperty("fileName", fileName);
            info.addProperty("sizeBytes", Files.size(newest));
            info.addProperty("stagedAt", Files.getLastModifiedTime(newest).toInstant().toString());
            Path shaFile = stagedDir.resolve(fileName + ".sha256");
            if (Files.isRegularFile(shaFile)) {
                String shaRaw = Files.readString(shaFile, StandardCharsets.UTF_8).trim();
                if (shaRaw.length() >= 64) {
                    info.addProperty("sha256", shaRaw.substring(0, 64).toLowerCase(Locale.ROOT));
                }
            }
            Path metaFile = stagedDir.resolve(fileName + ".version");
            if (Files.isRegularFile(metaFile)) {
                String ver = Files.readString(metaFile, StandardCharsets.UTF_8).trim();
                if (!ver.isBlank()) {
                    info.addProperty("version", ver);
                }
            }
            return info;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void appendCliInstructions(JsonObject obj) {
        obj.addProperty("linuxCheckCommand", "sudo bash oscar.sh update check");
        obj.addProperty("linuxUpgradeCommand", "sudo bash oscar.sh update apply");
        obj.addProperty("linuxEnableBridgeCommand", "sudo bash oscar.sh update enable-ui-bridge");
        obj.addProperty("windowsCheckCommand", ".\\oscar.bat update check");
        obj.addProperty("windowsUpgradeCommand", ".\\oscar.bat update apply");
        obj.addProperty("windowsEnableBridgeCommand", ".\\oscar.bat update enable-ui-bridge");
    }

    public synchronized JsonObject queueGitHubUpgrade(
            String targetVersion,
            String expectedSha256,
            boolean createBackupFirst,
            String username) throws IOException {
        assertOperationalUpgradeAllowed();
        if (targetVersion == null || !SystemUpdateReleaseChecker.isValidSemVer(targetVersion.trim())) {
            throw new IllegalArgumentException("A valid semantic version (for example 3.11.10) is required.");
        }
        String cleanVer = targetVersion.trim();
        if (!SystemUpdateReleaseChecker.isInPlaceCompatible311Plus(cleanVer)) {
            throw new IllegalArgumentException(
                    "In-place GUI upgrade supports OSCAR 3.11.0 and newer releases only (received " + cleanVer + ").");
        }
        String cleanSha = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(Locale.ROOT);
        if (!cleanSha.isEmpty() && !SHA256_PATTERN.matcher(cleanSha).matches()) {
            throw new IllegalArgumentException("expectedSha256 must be a 64-character hexadecimal SHA-256 digest.");
        }

        JsonObject currentStatus = readStatus(null);
        assertNoActiveUpgradeRunning(currentStatus);
        assertDiskSpaceSufficient(currentStatus);
        if (!optBoolean(currentStatus, "agentActive", false)) {
            throw new IllegalStateException(
                    "Host System Update agent is not active yet on this server. "
                            + "Run `sudo bash oscar.sh update apply` (Ubuntu) or `.\\oscar.bat update apply` (Windows), "
                            + "or enable 1-click GUI updates once with `sudo bash oscar.sh update enable-ui-bridge`.");
        }

        Map<String, Object> req = new LinkedHashMap<>();
        String requestId = UUID.randomUUID().toString();
        req.put("requestId", requestId);
        req.put("requestedAt", Instant.now().toString());
        req.put("requestedBy", (username == null || username.isBlank()) ? "admin" : username);
        req.put("action", "upgrade-github");
        req.put("targetVersion", cleanVer);
        req.put("expectedSha256", cleanSha);
        req.put("createBackupFirst", createBackupFirst);

        return writeRequestAndAwaitPickup(req, requestId, cleanVer);
    }

    public synchronized JsonObject stageUploadedBundleAndMaybeApply(
            InputStream bodyStream,
            String rawFileName,
            String expectedSha256Header,
            boolean createBackupFirst,
            boolean applyImmediately,
            String username) throws IOException {
        assertOperationalUpgradeAllowed();
        if (bodyStream == null) {
            throw new IllegalArgumentException("Release .zip upload stream is empty.");
        }
        String cleanExpectedSha = expectedSha256Header == null
                ? ""
                : expectedSha256Header.trim().toLowerCase(Locale.ROOT);
        if (!cleanExpectedSha.isEmpty() && !SHA256_PATTERN.matcher(cleanExpectedSha).matches()) {
            throw new IllegalArgumentException("X-Oscar-Expected-Sha256 must be a 64-character hex SHA-256 digest.");
        }

        JsonObject currentStatus = readStatus(null);
        assertNoActiveUpgradeRunning(currentStatus);
        assertDiskSpaceSufficient(currentStatus);

        Files.createDirectories(stagedDir);
        String uploadId = UUID.randomUUID().toString();
        Path partPath = stagedDir.resolve("upload-" + uploadId + ".zip.part");

        long totalBytes = 0L;
        String computedSha256;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65_536];
            try (OutputStream out = Files.newOutputStream(partPath)) {
                int r;
                while ((r = bodyStream.read(buf)) != -1) {
                    totalBytes += r;
                    if (totalBytes > MAX_UPLOAD_BYTES) {
                        throw new IllegalArgumentException("Uploaded bundle exceeds the 2 GiB release archive limit.");
                    }
                    md.update(buf, 0, r);
                    out.write(buf, 0, r);
                }
            }
            if (totalBytes < 128L) {
                throw new IllegalArgumentException("Uploaded file is too small to be a valid OSCAR release .zip archive.");
            }
            computedSha256 = HexFormat.of().formatHex(md.digest());
            if (!cleanExpectedSha.isEmpty() && !cleanExpectedSha.equals(computedSha256)) {
                throw new IllegalArgumentException(
                        "SHA-256 checksum mismatch on uploaded bundle (expected "
                                + cleanExpectedSha + ", computed " + computedSha256 + ").");
            }

            ValidatedZipMeta zipMeta = inspectAndValidateReleaseZip(partPath);
            String finalName = sanitizeBundleFileName(rawFileName, zipMeta.version);
            Path finalZip = stagedDir.resolve(finalName);

            // Remove older staged zips to avoid accumulating disk usage across upgrades.
            try (var existing = Files.list(stagedDir)) {
                for (Path p : existing.toList()) {
                    String fn = p.getFileName().toString();
                    if (!p.equals(partPath) && (fn.endsWith(".zip") || fn.endsWith(".sha256") || fn.endsWith(".version"))) {
                        Files.deleteIfExists(p);
                    }
                }
            }

            Files.move(partPath, finalZip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(
                    stagedDir.resolve(finalName + ".sha256"),
                    computedSha256 + " *" + finalName + "\n",
                    StandardCharsets.UTF_8);
            Files.writeString(
                    stagedDir.resolve(finalName + ".version"),
                    zipMeta.version + "\n",
                    StandardCharsets.UTF_8);

            if (applyImmediately && optBoolean(currentStatus, "agentActive", false)) {
                Map<String, Object> req = new LinkedHashMap<>();
                String requestId = UUID.randomUUID().toString();
                req.put("requestId", requestId);
                req.put("requestedAt", Instant.now().toString());
                req.put("requestedBy", (username == null || username.isBlank()) ? "admin" : username);
                req.put("action", "upgrade-staged");
                req.put("bundleFileName", finalName);
                req.put("targetVersion", zipMeta.version);
                req.put("expectedSha256", computedSha256);
                req.put("createBackupFirst", createBackupFirst);
                return writeRequestAndAwaitPickup(req, requestId, zipMeta.version);
            }

            JsonObject stagedResp = readStatus(null);
            stagedResp.addProperty("stagedOnly", true);
            stagedResp.addProperty("stagedVersion", zipMeta.version);
            stagedResp.addProperty("stagedSha256", computedSha256);
            stagedResp.addProperty("stagedFileName", finalName);
            return stagedResp;
        } catch (NoSuchAlgorithmException nsae) {
            throw new IOException("SHA-256 unavailable in JVM", nsae);
        } finally {
            Files.deleteIfExists(partPath);
        }
    }

    private record ValidatedZipMeta(String rootPrefix, String version) {
    }

    static ValidatedZipMeta inspectAndValidateReleaseZip(Path zipPath) throws IOException {
        String topRoot = null;
        boolean hasSetupSh = false;
        boolean hasComposeYaml = false;
        String envVersion = null;

        try (ZipFile zf = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank() || name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IllegalArgumentException("Rejected unsafe path inside release archive: " + name);
                }
                int slash = name.indexOf('/');
                if (slash <= 0) {
                    throw new IllegalArgumentException(
                            "OSCAR release archive must expand inside a single top-level oscar-<version>/ directory.");
                }
                String prefix = name.substring(0, slash);
                if (topRoot == null) {
                    topRoot = prefix;
                } else if (!topRoot.equals(prefix)) {
                    throw new IllegalArgumentException(
                            "OSCAR release archive contains multiple top-level directories (" + topRoot + " vs " + prefix + ").");
                }
                String rel = name.substring(slash + 1);
                if ("oscar-setup.sh".equals(rel)) {
                    hasSetupSh = true;
                } else if ("compose.yaml".equals(rel)) {
                    hasComposeYaml = true;
                } else if (".env.example".equals(rel) && !entry.isDirectory() && entry.getSize() < 65_536) {
                    try (InputStream in = zf.getInputStream(entry)) {
                        String envText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        for (String line : envText.split("\\R")) {
                            Matcher m = ENV_VERSION_LINE.matcher(line.trim());
                            if (m.matches()) {
                                envVersion = m.group(1);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            throw new IllegalArgumentException("Uploaded file is not a valid OSCAR release .zip archive: " + e.getMessage());
        }

        if (topRoot == null || !topRoot.startsWith("oscar-") || !hasSetupSh || !hasComposeYaml || envVersion == null) {
            throw new IllegalArgumentException(
                    "Uploaded .zip is missing required OSCAR release layout (oscar-<version>/.env.example, compose.yaml, oscar-setup.sh).");
        }
        if (!SystemUpdateReleaseChecker.isInPlaceCompatible311Plus(envVersion)) {
            throw new IllegalArgumentException(
                    "Uploaded bundle version " + envVersion + " is older than 3.11.0 and cannot be applied via in-place GUI update.");
        }
        return new ValidatedZipMeta(topRoot, envVersion);
    }

    private static String sanitizeBundleFileName(String rawFileName, String detectedVersion) {
        if (rawFileName != null && !rawFileName.isBlank()) {
            String base = Paths.get(rawFileName.trim()).getFileName().toString();
            if (SAFE_BUNDLE_NAME.matcher(base).matches() && base.startsWith("oscar-")) {
                return base;
            }
        }
        return "oscar-" + detectedVersion + ".zip";
    }

    private void assertOperationalUpgradeAllowed() {
        if (TrainingSandboxEnvironment.isSandboxMode()) {
            throw new IllegalStateException(
                    "System Update operates on the primary operational OSCAR portal (:443), not inside the disposable :8444 Training Sandbox.");
        }
        if (!isGuiUpdateEnabledByEnv()) {
            throw new SecurityException(
                    "GUI System Update is disabled by host configuration (OSCAR_GUI_UPDATE=disabled).");
        }
    }

    private static void assertNoActiveUpgradeRunning(JsonObject status) {
        String state = optString(status, "state").toUpperCase(Locale.ROOT);
        if ("RUNNING".equals(state) || "QUEUED".equals(state)) {
            throw new IllegalStateException("A system update is already in progress (" + optString(status, "stage") + ").");
        }
    }

    private static void assertDiskSpaceSufficient(JsonObject status) {
        double gib = MIN_FREE_DISK_GIB;
        if (status != null && status.has("availableDiskGib") && status.get("availableDiskGib").isJsonPrimitive()) {
            try {
                gib = status.get("availableDiskGib").getAsDouble();
            } catch (Exception ignored) {
            }
        }
        if (gib < MIN_FREE_DISK_GIB) {
            throw new IllegalStateException(String.format(
                    Locale.ROOT,
                    "Insufficient free disk space for safe system update (%.1f GiB available; at least %.1f GiB required).",
                    gib,
                    MIN_FREE_DISK_GIB));
        }
    }

    private JsonObject writeRequestAndAwaitPickup(Map<String, Object> req, String requestId, String targetVersion)
            throws IOException {
        Files.createDirectories(controlDir);
        Path tmp = controlDir.resolve("request.json.tmp." + requestId);
        Path target = controlDir.resolve("request.json");
        Files.writeString(tmp, GSON.toJson(req), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        long deadline = System.currentTimeMillis() + 1_600L;
        while (System.currentTimeMillis() < deadline) {
            JsonObject latest = readStatus(null);
            if (latest.has("activeJob") && latest.get("activeJob").isJsonObject()) {
                JsonObject job = latest.getAsJsonObject("activeJob");
                if (requestId.equals(optString(job, "requestId"))) {
                    return latest;
                }
            }
            try {
                Thread.sleep(120L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        JsonObject pending = readStatus(null);
        pending.addProperty("state", "QUEUED");
        pending.addProperty("stage", "QUEUED");
        pending.addProperty("progressPercent", 5);
        pending.addProperty("targetVersion", targetVersion);
        JsonObject job = new JsonObject();
        job.addProperty("requestId", requestId);
        job.addProperty("action", String.valueOf(req.get("action")));
        job.addProperty("state", "QUEUED");
        job.addProperty("targetVersion", targetVersion);
        job.addProperty("startedAt", Instant.now().toString());
        job.addProperty("message", "Queued for detached host System Update agent...");
        pending.add("activeJob", job);
        return pending;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        return obj.get(key).getAsString().trim();
    }

    private static boolean optBoolean(JsonObject obj, String key, boolean def) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return def;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return def;
        }
    }
}
