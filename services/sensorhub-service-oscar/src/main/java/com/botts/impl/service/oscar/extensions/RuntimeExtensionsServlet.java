/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.extensions;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Authenticated HTTP servlet mounted at {@code /sensorhub/oscar/extensions/*}.
 *
 * <ul>
 *   <li>Read-only active basemap metadata ({@code GET /maps/active}) and PMTiles byte-range
 *       streaming ({@code GET|HEAD /maps/tiles/<file.pmtiles>}) are accessible to all
 *       authenticated OSCAR users ({@code admin}, {@code operator}, {@code viewer}).</li>
 *   <li>All status, template, export, upload, activation, and reset-to-default operations
 *       require an authenticated {@code admin} role plus same-origin / {@code X-Requested-With}
 *       CSRF protection on state-changing requests.</li>
 * </ul>
 */
public final class RuntimeExtensionsServlet extends HttpServlet {

    public static final String ENDPOINT_WILDCARD = "/oscar/extensions/*";
    public static final String PUBLIC_SERVLET_PREFIX = "/sensorhub/oscar/extensions";
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(
                    java.time.Instant.class,
                    (com.google.gson.JsonSerializer<java.time.Instant>) (src, typeOfSrc, ctx) ->
                            src == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(src.toString()))
            .create();

    private final ISensorHub hub;
    private final RuntimeExtensionsManager manager;
    private final Logger logger;

    public RuntimeExtensionsServlet(ISensorHub hub, RuntimeExtensionsManager manager, Logger logger) {
        this.hub = hub;
        this.manager = manager;
        this.logger = logger;
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sub = normalizeSubpath(req);
        if (sub.startsWith("/maps/tiles/")) {
            if (!requireAuthenticatedUser(req, resp)) {
                return;
            }
            String rawFile = URLDecoder.decode(sub.substring("/maps/tiles/".length()), StandardCharsets.UTF_8);
            manager.getOfflineMapManager().servePmTilesBytes(rawFile, req, resp);
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sub = normalizeSubpath(req);

        // Authenticated viewer/operator/admin map endpoints
        if ("/maps/active".equals(sub)) {
            if (!requireAuthenticatedUser(req, resp)) {
                return;
            }
            sendJson(resp, HttpServletResponse.SC_OK,
                    manager.getOfflineMapManager().getPublicActiveMapInfo(resolvePublicPrefix(req)));
            return;
        }
        if (sub.startsWith("/maps/tiles/")) {
            if (!requireAuthenticatedUser(req, resp)) {
                return;
            }
            String rawFile = URLDecoder.decode(sub.substring("/maps/tiles/".length()), StandardCharsets.UTF_8);
            manager.getOfflineMapManager().servePmTilesBytes(rawFile, req, resp);
            return;
        }

        // All remaining GET endpoints require Administrator role
        if (!requireAdminUser(req, resp)) {
            return;
        }

        if (sub.isEmpty() || "/".equals(sub) || "/status".equals(sub)) {
            sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(resolvePublicPrefix(req)));
            return;
        }

        if ("/policy-packs/export".equals(sub)) {
            byte[] bytes = manager.getPolicyPackManager()
                    .exportCurrentPolicyJson(manager.getEffectiveCustomAdjudicationCodes())
                    .getBytes(StandardCharsets.UTF_8);
            sendDownload(resp, "application/json;charset=utf-8", "oscar-policy-pack.json", bytes);
            return;
        }

        if ("/templates/ocr-pack.zip".equals(sub)) {
            sendDownload(resp, "application/zip", "oscar-ocr-pack-template.zip", manager.buildOcrStarterTemplateZip());
            return;
        }
        if ("/templates/isotope-library.json".equals(sub)) {
            sendDownload(resp, "application/json;charset=utf-8", "oscar-isotope-library-template.json",
                    manager.buildIsotopeLibraryTemplateJson().getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("/templates/policy-pack.json".equals(sub)) {
            sendDownload(resp, "application/json;charset=utf-8", "oscar-policy-pack-template.json",
                    manager.buildPolicyPackTemplateJson().getBytes(StandardCharsets.UTF_8));
            return;
        }

        sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown runtime extensions endpoint: " + sub);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAdminUser(req, resp) || !requireCsrfHeader(req, resp)) {
            return;
        }
        String sub = normalizeSubpath(req);
        String prefix = resolvePublicPrefix(req);

        try {
            switch (sub) {
                case "/ocr/upload" -> {
                    String fn = requireFileName(req);
                    manager.uploadOcrPackage(fn, req.getInputStream());
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/ocr/action" -> {
                    JsonObject body = readJsonBody(req);
                    String action = getString(body, "action");
                    if ("reset".equals(action)) {
                        manager.resetOcrToDefault();
                        sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                    } else {
                        sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, "Unsupported OCR action: " + action);
                    }
                }
                case "/maps/upload" -> {
                    String fn = requireFileName(req);
                    String displayName = req.getParameter("displayName");
                    boolean defaultBasemap = !"false".equalsIgnoreCase(req.getParameter("defaultBasemap"));
                    manager.getOfflineMapManager().uploadAndActivate(
                            fn, displayName, defaultBasemap, req.getInputStream(), prefix);
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/maps/action" -> {
                    JsonObject body = readJsonBody(req);
                    String action = getString(body, "action");
                    switch (action) {
                        case "reset" -> manager.getOfflineMapManager().resetToDefault(prefix);
                        case "activate" -> manager.getOfflineMapManager().activateExisting(
                                getString(body, "fileName"),
                                body.has("defaultBasemap") && body.get("defaultBasemap").isJsonPrimitive()
                                        ? body.get("defaultBasemap").getAsBoolean()
                                        : null,
                                getString(body, "displayName"),
                                prefix);
                        case "set-default" -> manager.getOfflineMapManager().setDefaultBasemap(
                                body.has("defaultBasemap") && body.get("defaultBasemap").getAsBoolean(),
                                prefix);
                        case "delete-pack" -> manager.getOfflineMapManager().deleteStoredPack(
                                getString(body, "fileName"), prefix);
                        default -> {
                            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, "Unsupported maps action: " + action);
                            return;
                        }
                    }
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/spectroscopy/upload" -> {
                    String fn = requireFileName(req);
                    String mode = req.getParameter("executionMode");
                    manager.uploadSpectroscopyPackage(fn, mode, req.getInputStream());
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/spectroscopy/action" -> {
                    JsonObject body = readJsonBody(req);
                    String action = getString(body, "action");
                    if ("reset".equals(action)) {
                        manager.resetSpectroscopyToDefault();
                    } else if ("set-mode".equals(action)) {
                        manager.setSpectroscopyMode(getString(body, "executionMode"));
                    } else {
                        sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, "Unsupported spectroscopy action: " + action);
                        return;
                    }
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/trusted-certs/upload" -> {
                    String fn = requireFileName(req);
                    manager.uploadTrustedCertificate(fn, req.getInputStream());
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/trusted-certs/action" -> {
                    JsonObject body = readJsonBody(req);
                    String action = getString(body, "action");
                    if ("reset".equals(action)) {
                        manager.resetTrustedCertificatesToDefault();
                    } else if ("delete-cert".equals(action)) {
                        manager.deleteTrustedCertificate(getString(body, "fileName"));
                    } else {
                        sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, "Unsupported trusted-certs action: " + action);
                        return;
                    }
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/policy-packs/upload" -> {
                    String fn = requireFileName(req);
                    manager.uploadPolicyPack(fn, req.getInputStream());
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/policy-packs/action" -> {
                    JsonObject body = readJsonBody(req);
                    String action = getString(body, "action");
                    if ("reset".equals(action)) {
                        manager.resetPolicyPack();
                    } else {
                        sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, "Unsupported policy-packs action: " + action);
                        return;
                    }
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/eml/upload" -> {
                    String fn = requireFileName(req);
                    manager.uploadEmlPlugin(fn, req.getInputStream());
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                case "/eml/action" -> {
                    JsonObject body = readJsonBody(req);
                    String action = getString(body, "action");
                    if ("reset".equals(action) || "unload".equals(action)) {
                        manager.unloadEmlPlugin();
                    } else {
                        sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, "Unsupported EML action: " + action);
                        return;
                    }
                    sendJson(resp, HttpServletResponse.SC_OK, manager.getFullStatus(prefix));
                }
                default -> sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown runtime extensions action path: " + sub);
            }
        } catch (SecurityException se) {
            logger.warn("Security policy rejected runtime extension upload on {}: {}", sub, se.getMessage());
            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, se.getMessage());
        } catch (IllegalArgumentException iae) {
            logger.warn("Invalid runtime extension request on {}: {}", sub, iae.getMessage());
            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, iae.getMessage());
        } catch (Exception e) {
            logger.error("Runtime extension operation failed on {}: {}", sub, e.getMessage(), e);
            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST,
                    e.getMessage() != null ? e.getMessage() : "Extension validation failed");
        }
    }

    private boolean requireAuthenticatedUser(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String user = resolveUsername(req);
        if (user == null || user.isBlank()) {
            sendJsonError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return false;
        }
        return true;
    }

    private boolean requireAdminUser(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String user = resolveUsername(req);
        if (user == null || user.isBlank()) {
            sendJsonError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return false;
        }
        ISecurityManager sm = hub != null ? hub.getSecurityManager() : null;
        IUserInfo info = sm != null ? sm.getUserInfo(user) : null;
        if (info == null || info.getRoles() == null || !info.getRoles().contains("admin")) {
            sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN,
                    "Administrator role is required to manage OSCAR runtime extensions.");
            return false;
        }
        return true;
    }

    private static boolean requireCsrfHeader(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String xrw = req.getHeader("X-Requested-With");
        String secFetchSite = req.getHeader("Sec-Fetch-Site");
        if ("OSCAR-Viewer".equals(xrw) || "XMLHttpRequest".equalsIgnoreCase(xrw)) {
            return true;
        }
        if (secFetchSite != null && Set.of("same-origin", "none").contains(secFetchSite.trim().toLowerCase())) {
            return true;
        }
        // Allow non-browser CLI/API clients that do not send Origin/Sec-Fetch-Site headers
        if (req.getHeader("Origin") == null && secFetchSite == null) {
            return true;
        }
        sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN,
                "Cross-origin state-changing request rejected (missing X-Requested-With: OSCAR-Viewer header).");
        return false;
    }

    private static String resolveUsername(HttpServletRequest req) {
        if (req.getUserPrincipal() != null && req.getUserPrincipal().getName() != null) {
            return req.getUserPrincipal().getName();
        }
        return req.getRemoteUser();
    }

    private static String requireFileName(HttpServletRequest req) {
        String fn = req.getParameter("filename");
        if (fn == null || fn.isBlank()) {
            fn = req.getParameter("fileName");
        }
        if (fn == null || fn.isBlank()) {
            fn = req.getHeader("X-File-Name");
        }
        if (fn == null || fn.isBlank()) {
            throw new IllegalArgumentException("Missing 'filename' query parameter or 'X-File-Name' header");
        }
        return fn.trim();
    }

    private static JsonObject readJsonBody(HttpServletRequest req) throws IOException {
        String raw = new String(req.getInputStream().readNBytes(262_144), StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(raw);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static String getString(JsonObject obj, String key) {
        if (obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString().trim();
        }
        return "";
    }

    private static String normalizeSubpath(HttpServletRequest req) {
        String info = req.getPathInfo();
        if (info == null || info.isBlank()) {
            return "";
        }
        String norm = info.replace('\\', '/');
        while (norm.length() > 1 && norm.endsWith("/")) {
            norm = norm.substring(0, norm.length() - 1);
        }
        return norm;
    }

    private static String resolvePublicPrefix(HttpServletRequest req) {
        String ctx = req.getContextPath() == null ? "" : req.getContextPath();
        String sp = req.getServletPath() == null ? "" : req.getServletPath();
        String combined = (ctx + sp).replaceAll("/+$", "");
        return combined.isEmpty() ? PUBLIC_SERVLET_PREFIX : combined;
    }

    private static void sendDownload(HttpServletResponse resp, String contentType, String fileName, byte[] payload) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(contentType);
        resp.setContentLength(payload.length);
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.getOutputStream().write(payload);
        resp.getOutputStream().flush();
    }

    private static void sendJson(HttpServletResponse resp, int status, Object payload) throws IOException {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("application/json;charset=utf-8");
        resp.setContentLength(bytes.length);
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.getOutputStream().write(bytes);
        resp.getOutputStream().flush();
    }

    private static void sendJsonError(HttpServletResponse resp, int status, String message) throws IOException {
        sendJson(resp, status, Map.of("error", message == null ? "Request failed" : message));
    }
}
