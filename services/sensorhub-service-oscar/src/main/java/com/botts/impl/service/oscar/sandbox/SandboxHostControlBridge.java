/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Schema-validated file-queue bridge between authenticated OSCAR Administrator
 * sessions ({@code /sensorhub/oscar/sandbox/host}) and the optional host-side
 * {@code oscar-sandbox} watcher ({@code oscar-sandbox.sh} / {@code oscar-sandbox.ps1})
 * without exposing {@code docker.sock} or elevated privileges inside the
 * unprivileged {@code oscar} container.
 */
public final class SandboxHostControlBridge {

    public static final String PROP_CONTROL_DIR = "oscar.sandbox.controlDir";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> ALLOWED_ACTIONS = Set.of("start", "stop", "reset", "refresh-status");

    private final Path controlDir;
    private final Logger log;

    public SandboxHostControlBridge(Logger log) {
        this(resolveDefaultControlDir(), log);
    }

    public SandboxHostControlBridge(Path controlDir, Logger log) {
        this.controlDir = controlDir.toAbsolutePath().normalize();
        this.log = log;
    }

    private static Path resolveDefaultControlDir() {
        String override = System.getProperty(PROP_CONTROL_DIR);
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        return Paths.get("/var/lib/oscar/.moduledata/sandbox-control");
    }

    public Path getControlDir() {
        return controlDir;
    }

    public JsonObject readStatus() {
        Path statusFile = controlDir.resolve("status.json");
        if (Files.isRegularFile(statusFile)) {
            try {
                String raw = Files.readString(statusFile, StandardCharsets.UTF_8);
                JsonElement parsed = JsonParser.parseString(raw);
                if (parsed != null && parsed.isJsonObject()) {
                    JsonObject obj = parsed.getAsJsonObject();
                    if (!obj.has("agentActive")) {
                        obj.addProperty("agentActive", true);
                    }
                    appendCliInstructions(obj);
                    return obj;
                }
            } catch (Exception e) {
                if (log != null) {
                    log.warn("Failed reading sandbox host status.json: {}", e.getMessage());
                }
            }
        }
        JsonObject fallback = new JsonObject();
        fallback.addProperty("agentActive", false);
        fallback.addProperty("sandboxRunning", TrainingSandboxEnvironment.isSandboxMode());
        fallback.addProperty("httpsPort", TrainingSandboxEnvironment.getSandboxHttpsPort());
        fallback.addProperty("ttlMinutes", TrainingSandboxEnvironment.getTtlMinutes());
        appendCliInstructions(fallback);
        return fallback;
    }

    private static void appendCliInstructions(JsonObject obj) {
        int port = obj.has("httpsPort") && obj.get("httpsPort").isJsonPrimitive()
                ? obj.get("httpsPort").getAsInt()
                : TrainingSandboxEnvironment.DEFAULT_SANDBOX_HTTPS_PORT;
        obj.addProperty("linuxStartCommand", "sudo bash oscar.sh sandbox start --port " + port);
        obj.addProperty("linuxStopCommand", "sudo bash oscar.sh sandbox stop");
        obj.addProperty("linuxEnableBridgeCommand", "sudo bash oscar.sh sandbox enable-ui-bridge");
        obj.addProperty("windowsStartCommand", ".\\oscar.bat sandbox start --port " + port);
        obj.addProperty("windowsStopCommand", ".\\oscar.bat sandbox stop");
        obj.addProperty("windowsEnableBridgeCommand", ".\\oscar.bat sandbox enable-ui-bridge");
    }

    public synchronized JsonObject submitAction(JsonObject body, String username) throws IOException {
        return submitAction(body, username, true);
    }

    public synchronized JsonObject submitAction(JsonObject body, String username, boolean isAdmin) throws IOException {
        if (body == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        String action = optString(body, "action").toLowerCase(Locale.ROOT);
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unsupported sandbox host action: " + action);
        }
        if (!isAdmin && ("stop".equals(action) || "reset".equals(action))) {
            throw new SecurityException("Administrator role is required to stop or reset an active Training Sandbox session.");
        }

        JsonObject currentStatus = readStatus();
        int configuredPort = optInt(currentStatus, "httpsPort", TrainingSandboxEnvironment.DEFAULT_SANDBOX_HTTPS_PORT);
        int port = isAdmin
                ? optInt(body, "httpsPort", configuredPort)
                : configuredPort;
        int requestedTtl = optInt(body, "ttlMinutes", TrainingSandboxEnvironment.DEFAULT_TTL_MINUTES);
        int ttlMinutes = isAdmin ? requestedTtl : Math.min(requestedTtl, TrainingSandboxEnvironment.DEFAULT_TTL_MINUTES);
        if (port < 1024 || port > 65535 || port == 8443 || port == 443) {
            throw new IllegalArgumentException("Sandbox HTTPS port must be between 1024 and 65535 and must not collide with 443 or 8443.");
        }
        if (ttlMinutes < 15 || ttlMinutes > 480) {
            throw new IllegalArgumentException("Sandbox TTL must be between 15 and 480 minutes.");
        }

        if ("start".equals(action) && optBoolean(currentStatus, "sandboxRunning", false)) {
            JsonObject alreadyRunning = currentStatus.deepCopy();
            alreadyRunning.addProperty("canStopOrReset", isAdmin);
            JsonObject job = new JsonObject();
            job.addProperty("requestId", "noop-already-running");
            job.addProperty("action", "start");
            job.addProperty("state", "COMPLETED");
            job.addProperty("startedAt", Instant.now().toString());
            job.addProperty("message", "Training Sandbox is already running on port " + configuredPort
                    + ". Open it in a new tab to join without resetting active practice data.");
            alreadyRunning.add("activeJob", job);
            return alreadyRunning;
        }

        if (!optBoolean(currentStatus, "agentActive", false)) {
            throw new IllegalStateException(
                    "Host Training Sandbox UI bridge is not enabled on this server. "
                            + "An Administrator can start the sandbox via CLI (`sudo bash oscar.sh sandbox start` or `.\\oscar.bat sandbox start`), "
                            + "or enable 1-click browser launch once with `sudo bash oscar.sh sandbox enable-ui-bridge`.");
        }

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("requestId", requestId);
        req.put("requestedAt", Instant.now().toString());
        req.put("requestedBy", username == null || username.isBlank() ? "user" : username);
        req.put("action", action);
        req.put("httpsPort", port);
        req.put("ttlMinutes", ttlMinutes);

        Files.createDirectories(controlDir);
        Path tmp = controlDir.resolve("request.json.tmp." + requestId);
        Path target = controlDir.resolve("request.json");
        Files.writeString(tmp, GSON.toJson(req), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            JsonObject latest = readStatus();
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

        JsonObject pending = readStatus();
        JsonObject job = new JsonObject();
        job.addProperty("requestId", requestId);
        job.addProperty("action", action);
        job.addProperty("state", "QUEUED");
        job.addProperty("startedAt", Instant.now().toString());
        job.addProperty("message", "Queued for host Training Sandbox agent...");
        pending.add("activeJob", job);
        return pending;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        return obj.get(key).getAsString().trim();
    }

    private static int optInt(JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return def;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return def;
        }
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
