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

import com.botts.impl.service.oscar.diagnostics.SupportBundleRedactor;
import com.botts.impl.service.oscar.spreadsheet.SpreadsheetParser;
import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.LaneConfig;
import com.botts.impl.system.lane.config.LaneOptionsConfig;
import com.botts.impl.system.lane.config.RapiscanRPMConfig;
import com.botts.impl.system.lane.config.WebRelayConfig;
import com.botts.impl.system.lane.config.WebRelayOperatingMode;
import com.botts.impl.system.lane.helpers.webrelay.LaneWebRelayController;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.security.IUserInfo;
import org.sensorhub.impl.SensorHub;
import org.slf4j.LoggerFactory;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class LaneWebRelayServletTest {

    private HttpServer mockRelay;
    private final int[] relayBits = new int[]{0, 0, 0, 0};
    private SensorHub realHub;

    @Before
    public void setUp() throws Exception {
        mockRelay = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockRelay.createContext("/stateFull.xml", this::handleRelayRequest);
        mockRelay.start();

        realHub = new SensorHub();
        realHub.start();
    }

    @After
    public void tearDown() {
        if (realHub != null) {
            realHub.stop();
        }
        if (mockRelay != null) {
            mockRelay.stop(0);
        }
    }

    @Test
    public void enforcesRbacCsrfSanitizesOperatorStatusControlsRelaysAndRoundTripsCsvAndRedactor() throws Exception {
        LaneConfig laneCfg = new LaneConfig();
        laneCfg.id = "lane-01-test-id";
        laneCfg.moduleClass = LaneSystem.class.getCanonicalName();
        laneCfg.name = "Lane01";
        laneCfg.uniqueID = "lane-01";
        laneCfg.autoStart = false;
        laneCfg.operationalViewKeys = List.of("east-gate", "entrance");
        laneCfg.laneOptionsConfig = new LaneOptionsConfig();
        laneCfg.webRelayConfig.enabled = false;

        LaneSystem loadedLane = (LaneSystem) realHub.getModuleRegistry().loadModule(laneCfg);
        assertNotNull(loadedLane);
        realHub.getModuleRegistry().initModule(laneCfg.id);
        realHub.getModuleRegistry().startModule(laneCfg.id);
        assertTrue(loadedLane.isStarted());

        RapiscanRPMConfig rpm = new RapiscanRPMConfig();
        rpm.remoteHost = "198.51.100.10";
        rpm.remotePort = 10001;
        loadedLane.getConfiguration().laneOptionsConfig.rpmConfig = rpm;

        ISensorHub wrappedHub = wrapHubWithRoles(realHub, Map.of(
                "admin", List.of("admin"),
                "operator1", List.of("operator")
        ));
        var logger = LoggerFactory.getLogger(LaneWebRelayServletTest.class);
        WebRelayQuadClient fastClient = new WebRelayQuadClient(Duration.ofMillis(1000), Duration.ofMillis(1500));
        LaneWebRelayServlet servlet = new LaneWebRelayServlet(wrappedHub, logger, fastClient);

        // 1. Unauthenticated -> 401
        MockResp unauth = invoke(servlet, "GET", "/status", null, Map.of(), null, Map.of());
        assertEquals(401, unauth.status);

        // 2. Admin saves config enabling WebRelay on Lane01 -> controller arms R1=1, R2=1!
        String savePayload = """
                {
                  "action": "save-config",
                  "laneUid": "urn:osh:system:lane:lane-01",
                  "enabled": true,
                  "mode": "AUTO",
                  "primaryHost": "127.0.0.1",
                  "primaryPort": %d,
                  "username": "admin",
                  "password": "relay-secret-value",
                  "alarmPulseSeconds": 6,
                  "suppressExtendedOccupancyGamma": true,
                  "soundOnNeutronAlarm": true,
                  "pollIntervalSeconds": 30
                }
                """.formatted(mockRelay.getAddress().getPort());

        // Missing CSRF header with cross-origin Origin must fail closed with 403
        MockResp csrfBlocked = invoke(
                servlet,
                "POST",
                "/action",
                "admin",
                Map.of(),
                savePayload.getBytes(StandardCharsets.UTF_8),
                Map.of("Origin", "https://evil.example")
        );
        assertEquals(403, csrfBlocked.status);

        // Operator cannot POST /action -> 403
        MockResp opForbidden = invoke(
                servlet,
                "POST",
                "/action",
                "operator1",
                Map.of(),
                savePayload.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(403, opForbidden.status);

        // Valid Admin save-config -> 200 OK & armed!
        MockResp saveOk = invoke(
                servlet,
                "POST",
                "/action",
                "admin",
                Map.of(),
                savePayload.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, saveOk.status);
        JsonObject saveJson = JsonParser.parseString(saveOk.bodyString()).getAsJsonObject();
        assertTrue(saveJson.get("ok").getAsBoolean());
        JsonObject laneObj = saveJson.getAsJsonObject("lane");
        assertTrue(laneObj.get("enabled").getAsBoolean());
        assertTrue(laneObj.get("primaryOnline").getAsBoolean());
        assertTrue(laneObj.get("passwordConfigured").getAsBoolean());
        assertFalse("Raw password must never be echoed in status JSON", saveOk.bodyString().contains("relay-secret-value"));
        assertArrayEquals(new int[]{1, 1, 0, 0}, relayBits);

        // 3. Operator GET /status receives sanitized health flags without host/credentials!
        MockResp opStatus = invoke(servlet, "GET", "/status", "operator1", Map.of(), null, Map.of());
        assertEquals(200, opStatus.status);
        JsonObject opStatusJson = JsonParser.parseString(opStatus.bodyString()).getAsJsonObject();
        assertFalse(opStatusJson.get("isAdmin").getAsBoolean());
        JsonArray opLanes = opStatusJson.getAsJsonArray("lanes");
        assertEquals(1, opLanes.size());
        JsonObject opLane = opLanes.get(0).getAsJsonObject();
        assertTrue(opLane.get("enabled").getAsBoolean());
        assertTrue(opLane.get("primaryOnline").getAsBoolean());
        assertFalse("Operator payload must omit primaryHost", opLane.has("primaryHost"));
        assertFalse("Operator payload must omit username", opLane.has("username"));
        assertFalse("Operator payload must omit passwordConfigured", opLane.has("passwordConfigured"));

        // 4. Admin test-connection reuses stored password when omitted
        String testConnBody = """
                {
                  "action": "test-connection",
                  "laneUid": "urn:osh:system:lane:lane-01",
                  "host": "127.0.0.1",
                  "port": %d
                }
                """.formatted(mockRelay.getAddress().getPort());
        MockResp testConnOk = invoke(
                servlet,
                "POST",
                "/action",
                "admin",
                Map.of(),
                testConnBody.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, testConnOk.status);
        JsonObject testConnJson = JsonParser.parseString(testConnOk.bodyString()).getAsJsonObject();
        assertTrue(testConnJson.get("ok").getAsBoolean());
        assertEquals("/stateFull.xml", testConnJson.get("endpointPath").getAsString());

        // 5. Admin manual control commands: pulse-gamma, latch-neutron, set-mode PASS_THROUGH
        MockResp pulseResp = invoke(
                servlet,
                "POST",
                "/action",
                "admin",
                Map.of(),
                """
                {"action":"control","laneUid":"Lane01","command":"pulse-gamma","durationSeconds":4}
                """.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, pulseResp.status);
        assertArrayEquals(new int[]{1, 1, 1, 0}, relayBits);

        MockResp latchNeutronResp = invoke(
                servlet,
                "POST",
                "/action",
                "admin",
                Map.of(),
                """
                {"action":"control","laneUid":"Lane01","command":"latch-neutron"}
                """.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, latchNeutronResp.status);
        assertArrayEquals(new int[]{1, 1, 0, 1}, relayBits);

        MockResp passThroughResp = invoke(
                servlet,
                "POST",
                "/action",
                "admin",
                Map.of(),
                """
                {"action":"control","laneUid":"Lane01","command":"set-mode","mode":"PASS_THROUGH"}
                """.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, passThroughResp.status);
        assertArrayEquals(new int[]{0, 0, 0, 0}, relayBits);

        // 6. SpreadsheetParser #WEBRELAY round-trip serialization (omitting plaintext password) & explicit import
        SpreadsheetParser parser = new SpreadsheetParser();
        String csv = parser.serialize(List.of(loadedLane.getConfiguration()));
        assertTrue(csv.contains("#WEBRELAY,lane-01,enabled,true"));
        assertTrue(csv.contains("#WEBRELAY,lane-01,mode,PASS_THROUGH"));
        assertFalse("Exported CSV #WEBRELAY directives must not leak WebRelay password",
                csv.contains("relay-secret-value"));

        List<LaneConfig> parsedLanes = parser.deserialize(csv + "#WEBRELAY,lane-01,password,imported-pass\n");
        assertEquals(1, parsedLanes.size());
        WebRelayConfig rt = parsedLanes.get(0).webRelayConfig;
        assertTrue(rt.enabled);
        assertEquals(WebRelayOperatingMode.PASS_THROUGH, rt.mode);
        assertEquals("127.0.0.1", rt.primaryHost);
        assertEquals(mockRelay.getAddress().getPort(), rt.primaryPort);
        assertEquals("admin", rt.username);
        assertEquals("imported-pass", rt.password);
        assertEquals(6, rt.alarmPulseSeconds);
        assertTrue(rt.suppressExtendedOccupancyGamma);
        assertTrue(rt.soundOnNeutronAlarm);
        assertEquals(30, rt.pollIntervalSeconds);

        // 7. SupportBundleRedactor scrubs WebRelay passwords
        JsonObject rawDiag = JsonParser.parseString("""
                {
                  "webRelayConfig": {
                    "primaryHost": "198.51.100.21",
                    "username": "admin",
                    "password": "relay-secret-value"
                  },
                  "webrelayPassword": "another-secret-value"
                }
                """).getAsJsonObject();
        SupportBundleRedactor.RedactionStats stats = new SupportBundleRedactor.RedactionStats();
        JsonObject redacted = SupportBundleRedactor.redactJson(rawDiag, stats).getAsJsonObject();
        assertEquals(SupportBundleRedactor.REDACTED,
                redacted.getAsJsonObject("webRelayConfig").get("password").getAsString());
        assertEquals(SupportBundleRedactor.REDACTED,
                redacted.get("webrelayPassword").getAsString());
        assertEquals("198.51.100.21",
                redacted.getAsJsonObject("webRelayConfig").get("primaryHost").getAsString());

        LaneWebRelayController ctrl = loadedLane.getWebRelayController();
        if (ctrl != null) {
            ctrl.close();
        }
    }

    private synchronized void handleRelayRequest(HttpExchange ex) throws IOException {
        try {
            String rawQuery = ex.getRequestURI().getRawQuery();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                for (String part : rawQuery.split("&")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2) {
                        int idx = switch (kv[0]) {
                            case "relay1State" -> 0;
                            case "relay2State" -> 1;
                            case "relay3State" -> 2;
                            case "relay4State" -> 3;
                            default -> -1;
                        };
                        if (idx >= 0) {
                            relayBits[idx] = ("1".equals(kv[1]) || "2".equals(kv[1])) ? 1 : 0;
                        }
                    }
                }
            }
            String xml = "<?xml version='1.0' encoding='utf-8'?><datavalues>"
                    + "<relay1state>" + relayBits[0] + "</relay1state>"
                    + "<relay2state>" + relayBits[1] + "</relay2state>"
                    + "<relay3state>" + relayBits[2] + "</relay3state>"
                    + "<relay4state>" + relayBits[3] + "</relay4state>"
                    + "</datavalues>";
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } finally {
            ex.close();
        }
    }

    private static ISensorHub wrapHubWithRoles(SensorHub delegate, Map<String, Collection<String>> userRoles) {
        ISecurityManager sm = (ISecurityManager) Proxy.newProxyInstance(
                ISecurityManager.class.getClassLoader(),
                new Class<?>[]{ISecurityManager.class},
                (proxy, method, args) -> {
                    if ("getUserInfo".equals(method.getName()) && args != null && args.length == 1) {
                        String u = (String) args[0];
                        Collection<String> roles = userRoles.get(u);
                        if (roles == null) {
                            return null;
                        }
                        return (IUserInfo) Proxy.newProxyInstance(
                                IUserInfo.class.getClassLoader(),
                                new Class<?>[]{IUserInfo.class},
                                (up, um, ua) -> switch (um.getName()) {
                                    case "getId" -> u;
                                    case "getRoles" -> roles;
                                    default -> null;
                                }
                        );
                    }
                    return null;
                }
        );
        return (ISensorHub) Proxy.newProxyInstance(
                ISensorHub.class.getClassLoader(),
                new Class<?>[]{ISensorHub.class},
                (proxy, method, args) -> {
                    if ("getSecurityManager".equals(method.getName())) {
                        return sm;
                    }
                    return method.invoke(delegate, args);
                }
        );
    }

    private static final class MockResp {
        int status = 200;
        final Map<String, String> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final StringWriter writerBuf = new StringWriter();

        String bodyString() {
            return body.size() > 0
                    ? body.toString(StandardCharsets.UTF_8)
                    : writerBuf.toString();
        }
    }

    private static MockResp invoke(
            LaneWebRelayServlet servlet,
            String httpMethod,
            String pathInfo,
            String username,
            Map<String, String> params,
            byte[] requestBytes,
            Map<String, String> reqHeaders
    ) throws Exception {
        byte[] payload = requestBytes == null ? new byte[0] : requestBytes;
        ByteArrayInputStream bais = new ByteArrayInputStream(payload);
        ServletInputStream sis = new ServletInputStream() {
            @Override public boolean isFinished() { return bais.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener readListener) {}
            @Override public int read() { return bais.read(); }
            @Override public int read(byte[] b, int off, int len) { return bais.read(b, off, len); }
        };

        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMethod" -> httpMethod;
                    case "getPathInfo" -> pathInfo;
                    case "getContextPath" -> "/sensorhub";
                    case "getServletPath" -> "/oscar/webrelay";
                    case "getRemoteUser" -> username;
                    case "getUserPrincipal" -> username == null ? null : (Principal) () -> username;
                    case "getParameter" -> params.get((String) args[0]);
                    case "getHeader" -> {
                        String k = (String) args[0];
                        for (var e : reqHeaders.entrySet()) {
                            if (e.getKey().equalsIgnoreCase(k)) {
                                yield e.getValue();
                            }
                        }
                        yield null;
                    }
                    case "getInputStream" -> sis;
                    default -> null;
                }
        );

        MockResp out = new MockResp();
        ServletOutputStream sos = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener writeListener) {}
            @Override public void write(int b) { out.body.write(b); }
            @Override public void write(byte[] b, int off, int len) { out.body.write(b, off, len); }
        };
        PrintWriter pw = new PrintWriter(out.writerBuf);

        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus" -> out.status = (Integer) args[0];
                        case "sendError" -> out.status = (Integer) args[0];
                        case "setHeader" -> out.headers.put((String) args[0], (String) args[1]);
                        case "setContentType" -> out.headers.put("Content-Type", (String) args[0]);
                        case "setContentLength" -> out.headers.put("Content-Length", String.valueOf(args[0]));
                        case "getOutputStream" -> { return sos; }
                        case "getWriter" -> { return pw; }
                    }
                    return null;
                }
        );

        if ("GET".equals(httpMethod)) {
            servlet.doGet(req, resp);
        } else if ("POST".equals(httpMethod)) {
            servlet.doPost(req, resp);
        }
        return out;
    }
}
