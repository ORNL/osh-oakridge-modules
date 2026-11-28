/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.security.IUserInfo;
import org.slf4j.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Administrator-only HTTP servlet mounted at {@code /sensorhub/oscar/system-update/*}.
 *
 * <ul>
 *   <li>{@code GET /status}: Current running version, host update-agent state,
 *       progress percentage, log tail, staged offline bundle, and cached GitHub
 *       release metadata.</li>
 *   <li>{@code POST /check}: Zero-login anonymous GitHub Releases check against
 *       {@code https://github.com/ORNL/oscar} (no GitHub account or token required).</li>
 *   <li>{@code POST /apply}: Queues a SHA-256-verified sibling-directory upgrade
 *       from GitHub Releases via the detached host {@code oscar-update} agent.</li>
 *   <li>{@code POST /upload-bundle}: Streams and validates a local/air-gapped
 *       {@code oscar-<version>.zip} archive into {@code update-control/staged/}
 *       and optionally triggers immediate upgrade.</li>
 * </ul>
 */
public final class SystemUpdateServlet extends HttpServlet {

    public static final String ENDPOINT_WILDCARD = "/oscar/system-update/*";
    public static final String PUBLIC_SERVLET_PREFIX = "/sensorhub/oscar/system-update";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ISensorHub hub;
    private final SystemUpdateHostBridge bridge;
    private final SystemUpdateReleaseChecker checker;
    private final Logger log;

    public SystemUpdateServlet(ISensorHub hub, Logger log) {
        this(hub, new SystemUpdateHostBridge(log), new SystemUpdateReleaseChecker(log), log);
    }

    public SystemUpdateServlet(
            ISensorHub hub,
            SystemUpdateHostBridge bridge,
            SystemUpdateReleaseChecker checker,
            Logger log) {
        this.hub = hub;
        this.bridge = bridge;
        this.checker = checker;
        this.log = log;
    }

    public SystemUpdateHostBridge getBridge() {
        return bridge;
    }

    public SystemUpdateReleaseChecker getChecker() {
        return checker;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAdminUser(req, resp)) {
            return;
        }
        String sub = normalizeSubpath(req);
        if ("/status".equals(sub) || "/".equals(sub)) {
            String cur = bridge.resolveCurrentVersion();
            String channel = bridge.getReleaseChannel();
            JsonObject cached = checker.getCachedReleaseIfFresh(cur, channel);
            sendJson(resp, HttpServletResponse.SC_OK, bridge.readStatus(cached));
            return;
        }
        sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown system-update endpoint: " + sub);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!verifyCsrfHeaderOrSameOrigin(req, resp)) {
            return;
        }
        if (!requireAdminUser(req, resp)) {
            return;
        }
        String sub = normalizeSubpath(req);
        String username = req.getRemoteUser();

        try {
            switch (sub) {
                case "/check" -> {
                    JsonObject body = parseJsonBody(req);
                    boolean force = !body.has("force")
                            || !body.get("force").isJsonPrimitive()
                            || body.get("force").getAsBoolean();
                    String requestedChannel = optStr(body, "channel");
                    String channel = requestedChannel.isBlank()
                            ? bridge.getReleaseChannel()
                            : SystemUpdateReleaseChecker.normalizeChannel(requestedChannel);
                    String cur = bridge.resolveCurrentVersion();
                    try {
                        JsonObject release = checker.checkLatestRelease(cur, channel, force);
                        sendJson(resp, HttpServletResponse.SC_OK, bridge.readStatus(release));
                    } catch (IOException ioe) {
                        JsonObject offlineResp = bridge.readStatus(null);
                        offlineResp.addProperty("error", ioe.getMessage());
                        offlineResp.addProperty("offlineUploadAvailable", true);
                        sendJson(resp, HttpServletResponse.SC_BAD_GATEWAY, offlineResp);
                    }
                }
                case "/channel" -> {
                    JsonObject body = parseJsonBody(req);
                    String requestedChannel = optStr(body, "channel");
                    String savedChannel = bridge.setReleaseChannel(requestedChannel, username);
                    String cur = bridge.resolveCurrentVersion();
                    JsonObject cached = checker.getCachedReleaseIfFresh(cur, savedChannel);
                    sendJson(resp, HttpServletResponse.SC_OK, bridge.readStatus(cached));
                }
                case "/apply" -> {
                    JsonObject body = parseJsonBody(req);
                    String targetVersion = optStr(body, "targetVersion");
                    String expectedSha256 = optStr(body, "expectedSha256");
                    boolean createBackupFirst = optBool(body, "createBackupFirst", false);
                    String requestedChannel = optStr(body, "channel");
                    String channel = requestedChannel.isBlank()
                            ? bridge.getReleaseChannel()
                            : SystemUpdateReleaseChecker.normalizeChannel(requestedChannel);

                    if (targetVersion.isBlank()) {
                        JsonObject latest = checker.checkLatestRelease(bridge.resolveCurrentVersion(), channel, false);
                        targetVersion = optStr(latest, "latestVersion");
                        if (expectedSha256.isBlank()) {
                            expectedSha256 = optStr(latest, "expectedSha256");
                        }
                    } else if (expectedSha256.isBlank()) {
                        JsonObject cached = checker.getCachedReleaseIfFresh(bridge.resolveCurrentVersion(), channel);
                        if (cached != null && targetVersion.equals(optStr(cached, "latestVersion"))) {
                            expectedSha256 = optStr(cached, "expectedSha256");
                        }
                    }

                    JsonObject queued = bridge.queueGitHubUpgrade(
                            targetVersion, expectedSha256, createBackupFirst, username);
                    JsonObject cached = checker.getCachedReleaseIfFresh(bridge.resolveCurrentVersion(), channel);
                    if (cached != null) {
                        queued.add("latestRelease", cached);
                    }
                    sendJson(resp, HttpServletResponse.SC_OK, queued);
                }
                case "/upload-bundle" -> {
                    String rawFileName = firstNonBlank(
                            req.getHeader("X-Oscar-Bundle-Filename"),
                            req.getParameter("filename"));
                    String expectedSha = firstNonBlank(
                            req.getHeader("X-Oscar-Expected-Sha256"),
                            req.getParameter("sha256"));
                    boolean backupFirst = parseBoolFlag(
                            firstNonBlank(req.getHeader("X-Oscar-Backup-First"), req.getParameter("backupFirst")),
                            false);
                    boolean applyNow = parseBoolFlag(
                            firstNonBlank(req.getHeader("X-Oscar-Apply-Immediately"), req.getParameter("apply")),
                            true);

                    JsonObject result = bridge.stageUploadedBundleAndMaybeApply(
                            req.getInputStream(),
                            rawFileName,
                            expectedSha,
                            backupFirst,
                            applyNow,
                            username);
                    sendJson(resp, HttpServletResponse.SC_OK, result);
                }
                default -> sendJsonError(
                        resp, HttpServletResponse.SC_NOT_FOUND, "Unknown system-update endpoint: " + sub);
            }
        } catch (SecurityException se) {
            sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN, se.getMessage());
        } catch (IllegalArgumentException iae) {
            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, iae.getMessage());
        } catch (IllegalStateException ise) {
            sendJsonError(resp, HttpServletResponse.SC_CONFLICT, ise.getMessage());
        } catch (Exception e) {
            if (log != null) {
                log.warn("System update endpoint {} failed: {}", sub, e.getMessage(), e);
            }
            sendJsonError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    e.getMessage() != null ? e.getMessage() : "System update request failed");
        }
    }

    private boolean requireAdminUser(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = req.getRemoteUser();
        if (username == null || username.isBlank()) {
            sendJsonError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return false;
        }
        if (isEffectiveAdmin(username, req)) {
            return true;
        }
        sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN, "Administrator role required");
        return false;
    }

    private boolean isEffectiveAdmin(String username, HttpServletRequest req) {
        if ("admin".equalsIgnoreCase(username) || req.isUserInRole("admin")) {
            return true;
        }
        if (hub == null || hub.getSecurityManager() == null) {
            return false;
        }
        ISecurityManager sec = hub.getSecurityManager();
        IUserInfo user = sec.getUserInfo(username);
        if (user == null || user.getRoles() == null) {
            return false;
        }
        for (String role : user.getRoles()) {
            if (role != null && ("admin".equalsIgnoreCase(role.trim()) || "*".equals(role.trim()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean verifyCsrfHeaderOrSameOrigin(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String xrw = req.getHeader("X-Requested-With");
        if (xrw != null && !xrw.isBlank()) {
            return true;
        }
        String origin = req.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            try {
                URI originUri = URI.create(origin);
                String hostHeader = req.getHeader("X-Forwarded-Host");
                if (hostHeader == null || hostHeader.isBlank()) {
                    hostHeader = req.getHeader("Host");
                }
                if (hostHeader != null && originUri.getAuthority() != null
                        && hostHeader.trim().equalsIgnoreCase(originUri.getAuthority().trim())) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN,
                "Missing X-Requested-With header or mismatched Origin on state-changing request");
        return false;
    }

    private static JsonObject parseJsonBody(HttpServletRequest req) throws IOException {
        String raw = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(raw);
        return (parsed != null && parsed.isJsonObject()) ? parsed.getAsJsonObject() : new JsonObject();
    }

    private static String normalizeSubpath(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.isBlank()) {
            return "/";
        }
        String trimmed = path.trim();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.startsWith("/") ? trimmed : ("/" + trimmed);
    }

    private static String optStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        return obj.get(key).getAsString().trim();
    }

    private static boolean optBool(JsonObject obj, String key, boolean def) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return def;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return "";
    }

    private static boolean parseBoolFlag(String val, boolean def) {
        if (val == null || val.isBlank()) {
            return def;
        }
        return "true".equalsIgnoreCase(val) || "1".equals(val) || "yes".equalsIgnoreCase(val);
    }

    private static void sendJson(HttpServletResponse resp, int statusCode, JsonObject payload) throws IOException {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(statusCode);
        resp.setContentType("application/json;charset=utf-8");
        resp.setContentLength(bytes.length);
        resp.setHeader("Cache-Control", "no-store");
        resp.getOutputStream().write(bytes);
    }

    private static void sendJsonError(HttpServletResponse resp, int statusCode, String message) throws IOException {
        JsonObject err = new JsonObject();
        err.addProperty("error", message == null ? "Request failed" : message);
        sendJson(resp, statusCode, err);
    }
}
