/*******************************************************************************

  The contents of this file are subject to the Mozilla Public License, v. 2.0.
  If a copy of the MPL was not distributed with this file, You can obtain one
  at http://mozilla.org/MPL/2.0/.

  Software distributed under the License is distributed on an "AS IS" basis,
  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
  for the specific language governing rights and limitations under the License.

  The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
  Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.

 ******************************************************************************/

package com.botts.impl.service.oscar.webrelay;

import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.LaneConfig;
import com.botts.impl.system.lane.config.WebRelayConfig;
import com.botts.impl.system.lane.config.WebRelayOperatingMode;
import com.botts.impl.system.lane.helpers.webrelay.LaneWebRelayController;
import com.botts.impl.system.lane.helpers.webrelay.LaneWebRelayController.ControllerStatusSnapshot;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient.EndpointSpec;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient.RelayStateSnapshot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.security.IUserInfo;
import org.sensorhub.impl.module.ModuleRegistry;
import org.slf4j.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticated HTTP servlet mounted at {@code /sensorhub/oscar/webrelay/*} for
 * per-lane ControlByWeb WebRelay-Quad health reporting, configuration, and
 * Administrator physical RPM lights &amp; sounder control.
 *
 * <ul>
 *   <li>{@code GET /status}: accessible to all authenticated OSCAR users; non-administrators
 *       receive only sanitized lane online/offline health flags (no IP addresses or credentials),
 *       while {@code admin} users receive full per-lane configuration and {@code Relay 1–4}
 *       coil readbacks.</li>
 *   <li>{@code POST /action}: strictly requires {@code admin} role plus same-origin /
 *       {@code X-Requested-With} CSRF protection.</li>
 * </ul>
 */
public final class LaneWebRelayServlet extends HttpServlet {

    public static final String ENDPOINT_WILDCARD = "/oscar/webrelay/*";
    public static final String PUBLIC_SERVLET_PREFIX = "/sensorhub/oscar/webrelay";

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(
                    java.time.Instant.class,
                    (com.google.gson.JsonSerializer<java.time.Instant>) (src, typeOfSrc, ctx) ->
                            src == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(src.toString()))
            .create();

    private final ISensorHub hub;
    private final Logger logger;
    private final WebRelayQuadClient adHocTestClient;

    public LaneWebRelayServlet(ISensorHub hub, Logger logger) {
        this(hub, logger, new WebRelayQuadClient(Duration.ofMillis(2500), Duration.ofMillis(3500)));
    }

    public LaneWebRelayServlet(ISensorHub hub, Logger logger, WebRelayQuadClient adHocTestClient) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.adHocTestClient = Objects.requireNonNull(adHocTestClient, "adHocTestClient");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String user = resolveUsername(req);
        if (user == null || user.isBlank()) {
            sendJsonError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }
        String sub = normalizeSubpath(req);
        if (sub.isEmpty() || "/".equals(sub) || "/status".equals(sub)) {
            boolean isAdmin = hasAdminRole(user);
            boolean forceRefresh = isAdmin && "true".equalsIgnoreCase(req.getParameter("refresh"));
            String targetLane = req.getParameter("laneUid");
            sendJson(resp, HttpServletResponse.SC_OK, buildStatusPayload(isAdmin, forceRefresh, targetLane));
            return;
        }
        sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown WebRelay endpoint: " + sub);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireAdminUser(req, resp) || !requireCsrfHeader(req, resp)) {
            return;
        }
        String sub = normalizeSubpath(req);
        if (!"/action".equals(sub)) {
            sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND, "Unknown WebRelay action path: " + sub);
            return;
        }

        try {
            JsonObject body = readJsonBody(req);
            String action = getString(body, "action").toLowerCase(Locale.ROOT);
            switch (action) {
                case "test-connection" -> {
                    Map<String, Object> testRes = handleTestConnection(body);
                    sendJson(resp, HttpServletResponse.SC_OK, testRes);
                }
                case "control" -> {
                    String laneUid = getString(body, "laneUid");
                    LaneSystem lane = requireLane(laneUid);
                    Map<String, Object> laneStatus = handleControlCommand(lane, body);
                    sendJson(resp, HttpServletResponse.SC_OK, Map.of(
                            "ok", true,
                            "lane", laneStatus,
                            "lanes", buildLaneList(true, false, null)
                    ));
                }
                case "save-config" -> {
                    String laneUid = getString(body, "laneUid");
                    LaneSystem lane = requireLane(laneUid);
                    Map<String, Object> laneStatus = handleSaveConfig(lane, body);
                    sendJson(resp, HttpServletResponse.SC_OK, Map.of(
                            "ok", true,
                            "lane", laneStatus,
                            "lanes", buildLaneList(true, false, null)
                    ));
                }
                default -> sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, "Unsupported WebRelay action: " + action);
            }
        } catch (IllegalArgumentException iae) {
            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST, iae.getMessage());
        } catch (Exception ex) {
            logger.warn("Lane WebRelay operation failed: {}", ex.getMessage(), ex);
            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST,
                    ex.getMessage() != null ? ex.getMessage() : "WebRelay operation failed");
        }
    }

    private Map<String, Object> handleTestConnection(JsonObject body) {
        String laneUid = getString(body, "laneUid");
        String host = getString(body, "host");
        int port = getIntOrDefault(body, "port", WebRelayConfig.DEFAULT_PORT);
        String username = getString(body, "username");
        String candidatePass = body.has("password") && body.get("password").isJsonPrimitive()
                ? body.get("password").getAsString()
                : "";

        if (candidatePass.isEmpty() && !laneUid.isEmpty()) {
            LaneSystem existing = findLane(laneUid);
            if (existing != null && existing.getConfiguration() != null && existing.getConfiguration().webRelayConfig != null) {
                WebRelayConfig saved = existing.getConfiguration().webRelayConfig;
                if (saved.password != null && !saved.password.isEmpty()
                        && (host.equalsIgnoreCase(saved.primaryHost) || host.equalsIgnoreCase(saved.ancillaryHost))) {
                    candidatePass = saved.password;
                    if (username.isEmpty() && saved.username != null) {
                        username = saved.username;
                    }
                }
            }
        }

        EndpointSpec spec = new EndpointSpec(host, WebRelayConfig.normalizePort(port), username, candidatePass);
        RelayStateSnapshot snap = adHocTestClient.readState(spec);
        if (!snap.online()) {
            throw new IllegalArgumentException("Unable to reach WebRelay at " + spec.host() + ":" + spec.port()
                    + " — " + snap.error());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("host", spec.host());
        out.put("port", spec.port());
        out.put("endpointPath", snap.endpointPath());
        out.put("latencyMs", snap.latencyMs());
        out.put("relay1", snap.relay1());
        out.put("relay2", snap.relay2());
        out.put("relay3", snap.relay3());
        out.put("relay4", snap.relay4());
        return out;
    }

    private Map<String, Object> handleControlCommand(LaneSystem lane, JsonObject body) {
        LaneWebRelayController controller = lane.getWebRelayController();
        if (controller == null) {
            throw new IllegalArgumentException(
                    "WebRelay control is not enabled on lane '" + lane.getConfiguration().name + "'. Enable and save WebRelay settings first.");
        }
        String command = getString(body, "command").toLowerCase(Locale.ROOT);
        Integer durationSec = body.has("durationSeconds") && body.get("durationSeconds").isJsonPrimitive()
                ? body.get("durationSeconds").getAsInt()
                : null;

        ControllerStatusSnapshot snap = switch (command) {
            case "pulse-gamma" -> controller.pulseGammaAndSound(durationSec);
            case "pulse-neutron" -> controller.pulseNeutronLight(durationSec);
            case "pulse-all" -> controller.pulseAllIndicators(durationSec);
            case "latch-gamma" -> controller.latchIndicators(true, false);
            case "latch-neutron" -> controller.latchIndicators(false, true);
            case "latch-all" -> controller.latchIndicators(true, true);
            case "silence", "arm" -> controller.silenceAndArm();
            case "pass-through" -> controller.releaseToPassThrough();
            case "refresh" -> controller.pollAndReconcileNow();
            case "set-mode" -> {
                String rawMode = getString(body, "mode").toUpperCase(Locale.ROOT);
                WebRelayOperatingMode nextMode = WebRelayOperatingMode.valueOf(rawMode);
                ControllerStatusSnapshot s = controller.setOperatingMode(nextMode);
                persistLaneConfiguration(lane);
                yield s;
            }
            default -> throw new IllegalArgumentException("Unsupported WebRelay control command: " + command);
        };

        if (!snap.primaryState().online()) {
            throw new IllegalArgumentException(
                    "Primary WebRelay on lane '" + lane.getConfiguration().name + "' is unreachable: " + snap.primaryState().error());
        }
        return serializeLaneEntry(lane, true, false);
    }

    private Map<String, Object> handleSaveConfig(LaneSystem lane, JsonObject body) {
        LaneConfig laneCfg = lane.getConfiguration();
        WebRelayConfig prev = laneCfg.webRelayConfig != null ? laneCfg.webRelayConfig : new WebRelayConfig();
        WebRelayConfig next = new WebRelayConfig();

        next.enabled = getBoolOrDefault(body, "enabled", prev.enabled);
        String rawMode = getString(body, "mode");
        if (!rawMode.isEmpty()) {
            next.mode = WebRelayOperatingMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } else {
            next.mode = prev.mode != null ? prev.mode : WebRelayOperatingMode.AUTO;
        }
        next.primaryHost = body.has("primaryHost") ? getString(body, "primaryHost") : prev.primaryHost;
        next.primaryPort = WebRelayConfig.normalizePort(getIntOrDefault(body, "primaryPort", prev.primaryPort));
        next.ancillaryEnabled = getBoolOrDefault(body, "ancillaryEnabled", prev.ancillaryEnabled);
        next.ancillaryHost = body.has("ancillaryHost") ? getString(body, "ancillaryHost") : prev.ancillaryHost;
        next.ancillaryPort = WebRelayConfig.normalizePort(getIntOrDefault(body, "ancillaryPort", prev.ancillaryPort));
        next.username = body.has("username") ? getString(body, "username") : prev.username;

        boolean clearPass = getBoolOrDefault(body, "clearPassword", false);
        String rawPass = body.has("password") && body.get("password").isJsonPrimitive()
                ? body.get("password").getAsString()
                : "";
        if (clearPass) {
            next.password = "";
        } else if (!rawPass.isEmpty()) {
            next.password = rawPass;
        } else {
            next.password = prev.password != null ? prev.password : "";
        }

        next.alarmPulseSeconds = WebRelayConfig.clamp(
                getIntOrDefault(body, "alarmPulseSeconds", prev.alarmPulseSeconds),
                WebRelayConfig.MIN_ALARM_PULSE_SECONDS,
                WebRelayConfig.MAX_ALARM_PULSE_SECONDS,
                WebRelayConfig.DEFAULT_ALARM_PULSE_SECONDS
        );
        next.suppressExtendedOccupancyGamma = getBoolOrDefault(
                body, "suppressExtendedOccupancyGamma", prev.suppressExtendedOccupancyGamma);
        next.soundOnNeutronAlarm = getBoolOrDefault(
                body, "soundOnNeutronAlarm", prev.soundOnNeutronAlarm);
        next.pollIntervalSeconds = WebRelayConfig.clamp(
                getIntOrDefault(body, "pollIntervalSeconds", prev.pollIntervalSeconds),
                WebRelayConfig.MIN_POLL_INTERVAL_SECONDS,
                WebRelayConfig.MAX_POLL_INTERVAL_SECONDS,
                WebRelayConfig.DEFAULT_POLL_INTERVAL_SECONDS
        );

        if (next.enabled) {
            WebRelayQuadClient.sanitizeHost(next.primaryHost);
            if (next.ancillaryEnabled) {
                WebRelayQuadClient.sanitizeHost(next.ancillaryHost);
            }
        }

        laneCfg.webRelayConfig = next;
        lane.refreshWebRelayRuntime();
        if (next.enabled && lane.getWebRelayController() != null) {
            lane.getWebRelayController().pollAndReconcileNow();
        }
        persistLaneConfiguration(lane);
        return serializeLaneEntry(lane, true, false);
    }

    private void persistLaneConfiguration(LaneSystem lane) {
        try {
            ModuleRegistry reg = hub.getModuleRegistry();
            if (reg != null && !reg.isShuttingDown()) {
                List<ModuleConfig> allConfigs = new ArrayList<>();
                for (var mod : reg.getLoadedModules()) {
                    if (mod != null && mod.getConfiguration() != null) {
                        allConfigs.add(mod.getConfiguration());
                    }
                }
                if (!allConfigs.isEmpty()) {
                    reg.saveConfiguration(allConfigs.toArray(new ModuleConfig[0]));
                }
            }
        } catch (Exception ex) {
            logger.warn("Unable to persist updated WebRelay config for lane {}: {}",
                    lane.getConfiguration().name, ex.getMessage());
        }
    }

    private Map<String, Object> buildStatusPayload(boolean isAdmin, boolean forceRefresh, String targetLaneUid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("isAdmin", isAdmin);
        out.put("lanes", buildLaneList(isAdmin, forceRefresh, targetLaneUid));
        return out;
    }

    private List<Map<String, Object>> buildLaneList(boolean isAdmin, boolean forceRefresh, String targetLaneUid) {
        List<LaneSystem> lanes = listAllLanes();
        List<Map<String, Object>> items = new ArrayList<>(lanes.size());
        for (LaneSystem lane : lanes) {
            String uid = resolveLaneUid(lane);
            boolean refreshThis = forceRefresh && (targetLaneUid == null || targetLaneUid.isBlank() || targetLaneUid.equals(uid));
            items.add(serializeLaneEntry(lane, isAdmin, refreshThis));
        }
        return items;
    }

    private Map<String, Object> serializeLaneEntry(LaneSystem lane, boolean isAdmin, boolean refreshFirst) {
        LaneConfig laneCfg = lane.getConfiguration();
        WebRelayConfig wr = (laneCfg != null && laneCfg.webRelayConfig != null)
                ? laneCfg.webRelayConfig
                : new WebRelayConfig();
        LaneWebRelayController ctrl = lane.getWebRelayController();
        ControllerStatusSnapshot snap = null;
        if (ctrl != null) {
            snap = refreshFirst ? ctrl.pollAndReconcileNow() : ctrl.getStatusSnapshot();
        }

        boolean primaryOnline = snap != null && snap.primaryState().online();
        boolean ancillaryOnline = snap != null && snap.ancillaryEnabled() && snap.ancillaryState().online();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("laneUid", resolveLaneUid(lane));
        m.put("laneName", laneCfg != null && laneCfg.name != null ? laneCfg.name : "");
        m.put("enabled", wr.enabled);
        m.put("mode", (wr.mode != null ? wr.mode : WebRelayOperatingMode.AUTO).name());
        m.put("primaryOnline", primaryOnline);
        m.put("ancillaryEnabled", wr.ancillaryEnabled);
        m.put("ancillaryOnline", ancillaryOnline);

        if (isAdmin) {
            m.put("operationalViews", laneCfg != null && laneCfg.operationalViewKeys != null ? laneCfg.operationalViewKeys : List.of());
            m.put("primaryHost", wr.primaryHost != null ? wr.primaryHost : "");
            m.put("primaryPort", WebRelayConfig.normalizePort(wr.primaryPort));
            m.put("ancillaryHost", wr.ancillaryHost != null ? wr.ancillaryHost : "");
            m.put("ancillaryPort", WebRelayConfig.normalizePort(wr.ancillaryPort));
            m.put("username", wr.username != null ? wr.username : "");
            m.put("passwordConfigured", wr.password != null && !wr.password.isEmpty());
            m.put("alarmPulseSeconds", wr.alarmPulseSeconds);
            m.put("suppressExtendedOccupancyGamma", wr.suppressExtendedOccupancyGamma);
            m.put("soundOnNeutronAlarm", wr.soundOnNeutronAlarm);
            m.put("pollIntervalSeconds", wr.pollIntervalSeconds);
            m.put("manualLatchActive", snap != null && snap.manualLatchActive());
            m.put("triggeredAlarmCount", snap != null ? snap.triggeredAlarmCount() : 0L);
            m.put("suppressedEmlCount", snap != null ? snap.suppressedEmlCount() : 0L);
            m.put("suppressedExtendedOccupancyCount", snap != null ? snap.suppressedExtendedOccupancyCount() : 0L);
            m.put("lastActionSummary", snap != null ? snap.lastActionSummary() : "");
            m.put("lastActionAt", snap != null ? snap.lastActionAt() : "");
            m.put("primaryState", serializeRelayState(snap != null ? snap.primaryState() : null));
            m.put("ancillaryState", serializeRelayState(snap != null ? snap.ancillaryState() : null));
        }
        return m;
    }

    private static Map<String, Object> serializeRelayState(RelayStateSnapshot state) {
        if (state == null) {
            return Map.of(
                    "online", false,
                    "relay1", 0,
                    "relay2", 0,
                    "relay3", 0,
                    "relay4", 0,
                    "latencyMs", 0L,
                    "endpointPath", "",
                    "error", "Disabled"
            );
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("online", state.online());
        s.put("relay1", state.relay1());
        s.put("relay2", state.relay2());
        s.put("relay3", state.relay3());
        s.put("relay4", state.relay4());
        s.put("latencyMs", state.latencyMs());
        s.put("endpointPath", state.endpointPath());
        s.put("error", state.error());
        return s;
    }

    private List<LaneSystem> listAllLanes() {
        if (hub.getModuleRegistry() == null) {
            return List.of();
        }
        List<LaneSystem> lanes = new ArrayList<>(hub.getModuleRegistry().getLoadedModules(LaneSystem.class));
        lanes.sort(Comparator.comparing(
                l -> l.getConfiguration() != null && l.getConfiguration().name != null ? l.getConfiguration().name : "",
                String.CASE_INSENSITIVE_ORDER
        ));
        return lanes;
    }

    private LaneSystem findLane(String uidOrName) {
        if (uidOrName == null || uidOrName.isBlank()) {
            return null;
        }
        String needle = uidOrName.trim();
        for (LaneSystem lane : listAllLanes()) {
            String uid = resolveLaneUid(lane);
            String name = lane.getConfiguration() != null ? lane.getConfiguration().name : "";
            if (needle.equalsIgnoreCase(uid) || needle.equalsIgnoreCase(name)) {
                return lane;
            }
        }
        return null;
    }

    private LaneSystem requireLane(String uidOrName) {
        LaneSystem lane = findLane(uidOrName);
        if (lane == null) {
            throw new IllegalArgumentException("Unknown lane identifier: " + uidOrName);
        }
        return lane;
    }

    private static String resolveLaneUid(LaneSystem lane) {
        if (lane.getUniqueIdentifier() != null && !lane.getUniqueIdentifier().isBlank()) {
            return lane.getUniqueIdentifier();
        }
        if (lane.getConfiguration() != null && lane.getConfiguration().uniqueID != null) {
            String u = lane.getConfiguration().uniqueID;
            return u.startsWith("urn:") ? u : "urn:osh:system:lane:" + u;
        }
        return "";
    }

    private boolean hasAdminRole(String username) {
        ISecurityManager sm = hub.getSecurityManager();
        if (sm == null) {
            return false;
        }
        IUserInfo info = sm.getUserInfo(username);
        return info != null && info.getRoles() != null && info.getRoles().contains("admin");
    }

    private boolean requireAdminUser(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String user = resolveUsername(req);
        if (user == null || user.isBlank()) {
            sendJsonError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return false;
        }
        if (!hasAdminRole(user)) {
            sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN,
                    "Administrator role is required to control or configure lane WebRelays.");
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

    private static int getIntOrDefault(JsonObject obj, String key, int fallback) {
        if (obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsInt();
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean getBoolOrDefault(JsonObject obj, String key, boolean fallback) {
        if (obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsBoolean();
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
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
