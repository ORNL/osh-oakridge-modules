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

package com.botts.impl.system.lane.helpers.webrelay;

import com.botts.impl.system.lane.LaneRelayControl;
import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.LaneConfig;
import com.botts.impl.system.lane.config.WebRelayConfig;
import com.botts.impl.system.lane.config.WebRelayOperatingMode;
import com.botts.impl.system.lane.helpers.webrelay.LaneWebRelayController.ControllerStatusSnapshot;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient.EndpointSpec;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient.RelayStateSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.opengis.swe.v20.DataBlock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.impl.utils.rad.model.Adjudication;
import org.sensorhub.impl.utils.rad.model.Occupancy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class WebRelayIntegrationTest {

    private MockWebRelayServer primaryMock;
    private MockWebRelayServer ancillaryMock;

    @Before
    public void setUp() throws Exception {
        // Primary simulates X-WR-4R3-E rev. K (/stateFull.xml supported, /state.xml closed/404)
        primaryMock = new MockWebRelayServer(WebRelayQuadClient.PATH_STATE_FULL_XML, "admin", "secret123");
        // Ancillary simulates X-410 (/state.xml supported, /stateFull.xml 404)
        ancillaryMock = new MockWebRelayServer(WebRelayQuadClient.PATH_STATE_XML, "admin", "secret123");
    }

    @After
    public void tearDown() {
        if (primaryMock != null) {
            primaryMock.close();
        }
        if (ancillaryMock != null) {
            ancillaryMock.close();
        }
    }

    @Test
    public void clientAutoNegotiatesEndpointsValidatesHostsAndEnforcesBasicAuth() {
        WebRelayQuadClient client = new WebRelayQuadClient(Duration.ofMillis(1000), Duration.ofMillis(1500));

        try {
            WebRelayQuadClient.sanitizeHost("https://webrelay-lane01.local");
            fail("Expected https:// rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("plain HTTP"));
        }

        // Wrong password -> 401 offline snapshot
        EndpointSpec badAuth = new EndpointSpec("127.0.0.1", primaryMock.port(), "admin", "wrong");
        RelayStateSnapshot unauthSnap = client.readState(badAuth);
        assertFalse(unauthSnap.online());
        assertTrue(unauthSnap.error().contains("401"));

        // Valid auth on /stateFull.xml
        EndpointSpec priSpec = new EndpointSpec("http://127.0.0.1/", primaryMock.port(), "admin", "secret123");
        assertEquals("127.0.0.1", priSpec.host());
        RelayStateSnapshot priSnap = client.readState(priSpec);
        assertTrue(priSnap.online());
        assertEquals(WebRelayQuadClient.PATH_STATE_FULL_XML, priSnap.endpointPath());
        assertTrue(priSnap.isPassThrough());

        // Valid auth + fallback to /state.xml on X-410 mock
        EndpointSpec ancSpec = new EndpointSpec("127.0.0.1", ancillaryMock.port(), "admin", "secret123");
        RelayStateSnapshot ancSnap = client.setArmedIntercept(ancSpec);
        assertTrue(ancSnap.online());
        assertEquals(WebRelayQuadClient.PATH_STATE_XML, ancSnap.endpointPath());
        assertTrue(ancSnap.isArmedIntercept());
        assertEquals(1, ancSnap.relay1());
        assertEquals(1, ancSnap.relay2());
        assertEquals(0, ancSnap.relay3());
        assertEquals(0, ancSnap.relay4());
    }

    @Test
    public void controllerHandlesAutoGatekeepingPowerBlipRearmingManualOverridesAndFailSafeClose() throws Exception {
        LaneConfig laneCfg = new LaneConfig();
        laneCfg.name = "Lane01";
        laneCfg.uniqueID = "lane-01";
        laneCfg.extendedOccupancyThresholdMinutes = 1;

        WebRelayConfig wr = new WebRelayConfig();
        wr.enabled = true;
        wr.mode = WebRelayOperatingMode.AUTO;
        wr.primaryHost = "127.0.0.1";
        wr.primaryPort = primaryMock.port();
        wr.ancillaryEnabled = true;
        wr.ancillaryHost = "127.0.0.1";
        wr.ancillaryPort = ancillaryMock.port();
        wr.username = "admin";
        wr.password = "secret123";
        wr.alarmPulseSeconds = 5;
        wr.suppressExtendedOccupancyGamma = true;
        wr.soundOnNeutronAlarm = false;
        wr.pollIntervalSeconds = 60;
        laneCfg.webRelayConfig = wr;

        LaneSystem lane = new LaneSystem();
        lane.setConfiguration(laneCfg);

        WebRelayQuadClient fastClient = new WebRelayQuadClient(Duration.ofMillis(1000), Duration.ofMillis(1500));
        try (LaneWebRelayController controller = new LaneWebRelayController(lane, null, wr, fastClient)) {
            ControllerStatusSnapshot armed = controller.pollAndReconcileNow();
            assertTrue(armed.primaryState().online());
            assertTrue(armed.ancillaryState().online());
            assertTrue("AUTO idle must arm R1=1,R2=1 on primary", armed.primaryState().isArmedIntercept());
            assertTrue("AUTO idle must arm R1=1,R2=1 on ancillary", armed.ancillaryState().isArmedIntercept());

            // Simulate power blip resetting primary relays back to 0,0,0,0 -> poll must auto-rearm!
            primaryMock.resetRelaysToZero();
            assertArrayEquals(new int[]{0, 0, 0, 0}, primaryMock.currentRelays());
            ControllerStatusSnapshot rearmed = controller.pollAndReconcileNow();
            assertTrue(rearmed.primaryState().isArmedIntercept());
            assertArrayEquals(new int[]{1, 1, 0, 0}, primaryMock.currentRelays());

            int queriesBeforeSuppressions = primaryMock.recordedQueries().size();

            // 1. Code 9999: EML Non-Threat -> suppressed!
            Occupancy emlOcc = new Occupancy.Builder()
                    .samplingTime(1000.0)
                    .occupancyCount(1)
                    .startTime(990.0)
                    .endTime(1000.0)
                    .neutronBackground(12.0)
                    .gammaAlarm(true)
                    .neutronAlarm(false)
                    .maxGammaCount(800)
                    .maxNeutronCount(2)
                    .adjudicatedIds(List.of(Adjudication.EML_NON_THREAT_MARKER))
                    .videoPaths(List.of())
                    .webIdObsIds(List.of())
                    .build();
            controller.evaluateOccupancyForRelays(emlOcc);
            assertEquals(1L, controller.getStatusSnapshot().suppressedEmlCount());
            assertEquals("EML Non-Threat must not fire WebRelay pulse", queriesBeforeSuppressions, primaryMock.recordedQueries().size());

            // 2. Code 9998: Extended Occupancy Gamma Alarm (>= 60s, gamma-only) -> suppressed when toggle=true!
            Occupancy extGammaOcc = new Occupancy.Builder()
                    .samplingTime(2000.0)
                    .occupancyCount(2)
                    .startTime(1920.0) // 80s >= 60s threshold
                    .endTime(2000.0)
                    .neutronBackground(12.0)
                    .gammaAlarm(true)
                    .neutronAlarm(false)
                    .maxGammaCount(650)
                    .maxNeutronCount(1)
                    .adjudicatedIds(List.of())
                    .videoPaths(List.of())
                    .webIdObsIds(List.of())
                    .build();
            controller.evaluateOccupancyForRelays(extGammaOcc);
            assertEquals(1L, controller.getStatusSnapshot().suppressedExtendedOccupancyCount());
            assertEquals("Extended Occupancy Gamma must not fire pulse when suppressed",
                    queriesBeforeSuppressions, primaryMock.recordedQueries().size());

            // 3. Actionable Gamma + Neutron alarm -> pulses R3 & R4 while keeping R1=1, R2=1 on BOTH units!
            Occupancy threatOcc = new Occupancy.Builder()
                    .samplingTime(3000.0)
                    .occupancyCount(3)
                    .startTime(2992.0)
                    .endTime(3000.0)
                    .neutronBackground(12.0)
                    .gammaAlarm(true)
                    .neutronAlarm(true)
                    .maxGammaCount(2400)
                    .maxNeutronCount(28)
                    .adjudicatedIds(List.of())
                    .videoPaths(List.of())
                    .webIdObsIds(List.of())
                    .build();
            controller.evaluateOccupancyForRelays(threatOcc);
            awaitCondition(() -> controller.getStatusSnapshot().triggeredAlarmCount() == 1L, 2000);
            String lastPriQuery = primaryMock.lastQuery();
            String lastAncQuery = ancillaryMock.lastQuery();
            assertTrue(lastPriQuery.contains("relay1State=1&relay2State=1&relay3State=2&pulseTime3=5&relay4State=2&pulseTime4=5"));
            assertTrue(lastAncQuery.contains("relay1State=1&relay2State=1&relay3State=2&pulseTime3=5&relay4State=2&pulseTime4=5"));

            // 4. Flip suppressExtendedOccupancyGamma = false -> extended gamma alarm now pulses Channel 1 (R3)!
            wr.suppressExtendedOccupancyGamma = false;
            controller.reconfigure(wr, null);
            controller.evaluateOccupancyForRelays(extGammaOcc);
            awaitCondition(() -> controller.getStatusSnapshot().triggeredAlarmCount() == 2L, 2000);
            assertTrue(primaryMock.lastQuery().contains("relay3State=2&pulseTime3=5&relay4State=0"));

            // 5. Manual latch, silence, and OSH LaneRelayControl command verification
            ControllerStatusSnapshot latched = controller.latchIndicators(true, false);
            assertTrue(latched.manualLatchActive());
            assertArrayEquals(new int[]{1, 1, 1, 0}, primaryMock.currentRelays());
            assertArrayEquals(new int[]{1, 1, 1, 0}, ancillaryMock.currentRelays());

            ControllerStatusSnapshot silenced = controller.silenceAndArm();
            assertFalse(silenced.manualLatchActive());
            assertArrayEquals(new int[]{1, 1, 0, 0}, primaryMock.currentRelays());

            // Exercise LaneRelayControl command interface through a test subclass exposing controller
            LaneSystem controlledLane = new LaneSystem() {
                @Override
                public LaneWebRelayController getWebRelayController() {
                    return controller;
                }
            };
            controlledLane.setConfiguration(laneCfg);
            LaneRelayControl relayControl = new LaneRelayControl(controlledLane) {};
            DataBlock cmdParams = relayControl.getCommandDescription().createDataBlock();
            cmdParams.setStringValue(0, LaneRelayControl.ACTION_PULSE_NEUTRON_LIGHT);
            cmdParams.setIntValue(1, 3);
            ICommandStatus cmdStatus = relayControl.submitCommand(
                    new CommandData.Builder()
                            .withId(org.sensorhub.api.common.BigId.fromLong(1, 1L))
                            .withCommandStream(org.sensorhub.api.common.BigId.fromLong(1, 1L))
                            .withParams(cmdParams)
                            .build()
            ).get(3, TimeUnit.SECONDS);
            assertEquals(ICommandStatus.CommandStatusCode.COMPLETED, cmdStatus.getStatusCode());
            assertTrue(primaryMock.lastQuery().contains("relay3State=0&relay4State=2&pulseTime4=3"));
        }

        // 6. Clean controller close() must release both units to fail-safe hardware pass-through (0,0,0,0)!
        assertArrayEquals(new int[]{0, 0, 0, 0}, primaryMock.currentRelays());
        assertArrayEquals(new int[]{0, 0, 0, 0}, ancillaryMock.currentRelays());
    }

    private static void awaitCondition(Check cond, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.ok()) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue("Condition timed out after " + timeoutMs + "ms", cond.ok());
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    static final class MockWebRelayServer implements AutoCloseable {
        private final HttpServer server;
        private final String supportedPath;
        private final String expectedAuthHeader;
        private final int[] relays = new int[]{0, 0, 0, 0};
        private final List<String> queries = new CopyOnWriteArrayList<>();

        MockWebRelayServer(String supportedPath, String username, String password) throws IOException {
            this.supportedPath = supportedPath;
            if (password != null && !password.isEmpty()) {
                String token = Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                this.expectedAuthHeader = "Basic " + token;
            } else {
                this.expectedAuthHeader = null;
            }
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        synchronized void resetRelaysToZero() {
            relays[0] = 0;
            relays[1] = 0;
            relays[2] = 0;
            relays[3] = 0;
        }

        synchronized int[] currentRelays() {
            return relays.clone();
        }

        List<String> recordedQueries() {
            return queries;
        }

        String lastQuery() {
            return queries.isEmpty() ? "" : queries.get(queries.size() - 1);
        }

        private synchronized void handle(HttpExchange ex) throws IOException {
            try {
                if (expectedAuthHeader != null) {
                    String got = ex.getRequestHeaders().getFirst("Authorization");
                    if (!expectedAuthHeader.equals(got)) {
                        ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"WebRelay\"");
                        ex.sendResponseHeaders(401, -1);
                        return;
                    }
                }
                String path = ex.getRequestURI().getPath();
                if (!supportedPath.equals(path)) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                String rawQuery = ex.getRequestURI().getRawQuery();
                if (rawQuery != null && !rawQuery.isEmpty()) {
                    queries.add(rawQuery);
                    for (String part : rawQuery.split("&")) {
                        String[] kv = part.split("=", 2);
                        if (kv.length == 2) {
                            applyRelayParam(kv[0], kv[1]);
                        }
                    }
                }
                String xml = "<?xml version='1.0' encoding='utf-8'?><datavalues>"
                        + "<relay1state>" + relays[0] + "</relay1state>"
                        + "<relay2state>" + relays[1] + "</relay2state>"
                        + "<relay3state>" + relays[2] + "</relay3state>"
                        + "<relay4state>" + relays[3] + "</relay4state>"
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

        private void applyRelayParam(String key, String val) {
            int idx = switch (key) {
                case "relay1State" -> 0;
                case "relay2State" -> 1;
                case "relay3State" -> 2;
                case "relay4State" -> 3;
                default -> -1;
            };
            if (idx >= 0) {
                // ControlByWeb state=1 (ON) and state=2 (PULSE) both report <relayNstate>1</relayNstate> while active
                relays[idx] = ("1".equals(val) || "2".equals(val)) ? 1 : 0;
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
