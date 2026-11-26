/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import com.botts.impl.system.lane.mobile.MobileDetectorSystem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authenticated HTTP servlet mounted at {@code /sensorhub/oscar/sandbox/*}.
 *
 * <ul>
 *   <li>{@code GET /active}: Fast ({@code < 1 ms}) sandbox detection for all authenticated
 *       users ({@code {"sandboxMode": false}} in production; full Training Coach state and
 *       verification snapshot when {@code OSCAR_SANDBOX_MODE=true}).</li>
 *   <li>{@code GET /samples/<file>}: Downloads built-in practice files inside sandbox mode.</li>
 *   <li>{@code POST /inject}, {@code POST /stage-track}, {@code POST /certificate}:
 *       Interactive Training Coach controls inside sandbox mode (CSRF-protected).</li>
 *   <li>{@code GET|POST /host}: Host sandbox launcher/status bridge available from
 *       {@code Guided Walkthrough} and {@code Help (?)}. Any authenticated user may inspect
 *       host status/shutdown countdown and queue an idempotent {@code start}; only
 *       Administrators may execute {@code stop} or {@code reset}.</li>
 * </ul>
 */
public final class TrainingSandboxServlet extends HttpServlet {

    public static final String ENDPOINT_WILDCARD = "/oscar/sandbox/*";
    public static final String PUBLIC_SERVLET_PREFIX = "/sensorhub/oscar/sandbox";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ISensorHub hub;
    private final TrainingSandboxManager manager;
    private final Logger log;

    public TrainingSandboxServlet(ISensorHub hub, TrainingSandboxManager manager, Logger log) {
        this.hub = hub;
        this.manager = manager;
        this.log = log;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sub = normalizeSubpath(req);

        if ("/active".equals(sub)) {
            if (!requireAuthenticatedUser(req, resp)) {
                return;
            }
            sendJson(resp, HttpServletResponse.SC_OK, enrichVerificationPayload(manager.getSandboxActiveStatus()));
            return;
        }

        if ("/host".equals(sub)) {
            if (!requireAuthenticatedUser(req, resp)) {
                return;
            }
            boolean isAdmin = isEffectiveAdmin(req.getRemoteUser(), req);
            JsonObject status = manager.getHostControlBridge().readStatus();
            status.addProperty("canStopOrReset", isAdmin);
            sendJson(resp, HttpServletResponse.SC_OK, status);
            return;
        }

        if (sub.startsWith("/samples/")) {
            if (!requireAuthenticatedUser(req, resp)) {
                return;
            }
            if (!TrainingSandboxEnvironment.isSandboxMode()) {
                sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN,
                        "Practice sample downloads are only active inside the Training Sandbox.");
                return;
            }
            String fileName = URLDecoder.decode(sub.substring("/samples/".length()), StandardCharsets.UTF_8);
            SandboxSampleAssets.SamplePayload payload = SandboxSampleAssets.buildSample(
                    fileName, manager.getRapiscanPort1(), manager.getRtspPort());
            if (payload == null) {
                sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown training sample file: " + fileName);
                return;
            }
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType(payload.contentType());
            resp.setContentLength(payload.bytes().length);
            resp.setHeader("Cache-Control", "no-store");
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + payload.fileName() + "\"");
            resp.getOutputStream().write(payload.bytes());
            return;
        }

        sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown sandbox endpoint: " + sub);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sub = normalizeSubpath(req);
        if (!verifyCsrfHeaderOrSameOrigin(req, resp)) {
            return;
        }

        if ("/host".equals(sub)) {
            if (!requireAuthenticatedUser(req, resp)) {
                return;
            }
            String username = req.getRemoteUser();
            boolean isAdmin = isEffectiveAdmin(username, req);
            try {
                JsonObject body = parseJsonBody(req);
                String action = optStr(body, "action").toLowerCase(Locale.ROOT);
                if (("stop".equals(action) || "reset".equals(action)) && !isAdmin) {
                    sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN,
                            "Administrator role required to stop or reset an active Training Sandbox.");
                    return;
                }
                JsonObject result = manager.getHostControlBridge().submitAction(body, username, isAdmin);
                result.addProperty("canStopOrReset", isAdmin);
                sendJson(resp, HttpServletResponse.SC_OK, result);
            } catch (SecurityException se) {
                sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN, se.getMessage());
            } catch (IllegalArgumentException iae) {
                sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, iae.getMessage());
            } catch (IllegalStateException ise) {
                sendJsonError(resp, HttpServletResponse.SC_CONFLICT, ise.getMessage());
            } catch (Exception e) {
                log.warn("Sandbox host control bridge action failed: {}", e.getMessage());
                sendJsonError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
            return;
        }

        if (!requireAuthenticatedUser(req, resp)) {
            return;
        }
        if (!TrainingSandboxEnvironment.isSandboxMode()) {
            sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN,
                    "Training Sandbox simulation controls are strictly disabled on operational nodes.");
            return;
        }

        try {
            switch (sub) {
                case "/inject" -> {
                    JsonObject body = parseJsonBody(req);
                    String scenario = body.has("scenario") && body.get("scenario").isJsonPrimitive()
                            ? body.get("scenario").getAsString()
                            : "";
                    Integer laneSlot = body.has("laneSlot") && body.get("laneSlot").isJsonPrimitive()
                            ? body.get("laneSlot").getAsInt()
                            : 1;
                    sendJson(resp, HttpServletResponse.SC_OK, manager.injectScenario(scenario, laneSlot));
                }
                case "/stage-track" -> {
                    JsonObject body = parseJsonBody(req);
                    String track = body.has("track") && body.get("track").isJsonPrimitive()
                            ? body.get("track").getAsString()
                            : TrainingSandboxManager.TRACK_OPERATOR;
                    sendJson(resp, HttpServletResponse.SC_OK, enrichVerificationPayload(manager.stageTrack(track)));
                }
                case "/certificate" -> {
                    JsonObject body = parseJsonBody(req);
                    String traineeName = body.has("traineeName") && body.get("traineeName").isJsonPrimitive()
                            ? body.get("traineeName").getAsString()
                            : "";
                    List<TrainingSandboxManager.CompletedLabRecord> records = new ArrayList<>();
                    if (body.has("completedLabs") && body.get("completedLabs").isJsonArray()) {
                        JsonArray arr = body.getAsJsonArray("completedLabs");
                        for (JsonElement el : arr) {
                            if (el != null && el.isJsonObject()) {
                                JsonObject o = el.getAsJsonObject();
                                records.add(new TrainingSandboxManager.CompletedLabRecord(
                                        optStr(o, "trackId"),
                                        optStr(o, "labId"),
                                        optStr(o, "title"),
                                        optStr(o, "status")));
                            }
                        }
                    }
                    byte[] pdfBytes = manager.renderCompletionCertificatePdf(traineeName, records);
                    resp.setStatus(HttpServletResponse.SC_OK);
                    resp.setContentType("application/pdf");
                    resp.setContentLength(pdfBytes.length);
                    resp.setHeader("Cache-Control", "no-store");
                    resp.setHeader("Content-Disposition",
                            "attachment; filename=\"oscar-training-completion-record.pdf\"");
                    resp.getOutputStream().write(pdfBytes);
                }
                default -> sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown sandbox endpoint: " + sub);
            }
        } catch (IllegalArgumentException iae) {
            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, iae.getMessage());
        } catch (IllegalStateException ise) {
            sendJsonError(resp, HttpServletResponse.SC_CONFLICT, ise.getMessage());
        } catch (Exception e) {
            log.warn("Training sandbox endpoint {} failed: {}", sub, e.getMessage(), e);
            sendJsonError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    e.getMessage() != null ? e.getMessage() : "Sandbox operation failed");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enrichVerificationPayload(Map<String, Object> statusPayload) {
        if (statusPayload == null || !Boolean.TRUE.equals(statusPayload.get("sandboxMode"))) {
            return statusPayload;
        }
        Object rawVer = statusPayload.get("verification");
        if (rawVer instanceof Map<?, ?> verMap) {
            Map<String, Object> verification = (Map<String, Object>) verMap;
            boolean configured = Boolean.TRUE.equals(verification.get("mobileDetectorConfigured"))
                    || hasActiveMobileDetectorModule();
            verification.put("mobileDetectorConfigured", configured);
        }
        return statusPayload;
    }

    private boolean hasActiveMobileDetectorModule() {
        if (hub == null || hub.getModuleRegistry() == null) {
            return false;
        }
        var mobiles = hub.getModuleRegistry().getLoadedModules(MobileDetectorSystem.class);
        if (mobiles != null) {
            for (MobileDetectorSystem mobile : mobiles) {
                if (mobile != null && mobile.isStarted()) {
                    return true;
                }
            }
        }
        var allLoaded = hub.getModuleRegistry().getLoadedModules();
        if (allLoaded != null) {
            for (var mod : allLoaded) {
                if (mod instanceof MobileDetectorSystem mobile && mobile.isStarted()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean requireAuthenticatedUser(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String user = req.getRemoteUser();
        if (user == null || user.isBlank()) {
            sendJsonError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return false;
        }
        return true;
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
        if (user == null) {
            return false;
        }
        if (user.getRoles() != null) {
            for (String role : user.getRoles()) {
                if (role != null && ("admin".equalsIgnoreCase(role.trim()) || "*".equals(role.trim()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean verifyCsrfHeaderOrSameOrigin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
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

    private static String optStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        return obj.get(key).getAsString().trim();
    }

    private static String normalizeSubpath(HttpServletRequest req) {
        String info = req.getPathInfo();
        if (info == null || info.isBlank() || "/".equals(info)) {
            return "/";
        }
        String clean = info.trim();
        while (clean.endsWith("/") && clean.length() > 1) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private static void sendJson(HttpServletResponse resp, int status, Object payload) throws IOException {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("application/json; charset=utf-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private static void sendJsonError(HttpServletResponse resp, int status, String message) throws IOException {
        sendJson(resp, status, Map.of("error", message == null ? "Error" : message));
    }
}
