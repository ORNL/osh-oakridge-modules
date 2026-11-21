/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import com.botts.impl.service.oscar.OSCARServiceConfig;
import com.botts.impl.service.oscar.eml.EmlPluginHandler;
import com.botts.impl.service.oscar.extensions.PmTilesHeader;
import com.botts.impl.service.oscar.extensions.RuntimeExtensionsManager;
import com.botts.impl.service.oscar.spreadsheet.SpreadsheetParser;
import com.botts.impl.service.oscar.uff.UffSeekableSource;
import com.botts.impl.service.oscar.uff.UffZipReader;
import com.botts.impl.service.oscar.uff.WcoUffModels;
import com.botts.impl.service.oscar.uff.WcoUffParser;
import com.botts.impl.system.lane.config.LaneConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.After;
import org.junit.Test;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.security.IUserInfo;
import org.sensorhub.impl.utils.rad.eml.EmlEvaluationResult;
import org.sensorhub.impl.utils.rad.eml.EmlLaneParameters;
import org.slf4j.LoggerFactory;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TrainingSandboxIntegrationTest {

    private Path tempRoot;

    @After
    public void cleanup() throws Exception {
        System.clearProperty(TrainingSandboxEnvironment.PROP_SANDBOX_MODE);
        System.clearProperty(TrainingSandboxManager.PROP_WORK_DIR);
        System.clearProperty(SandboxHostControlBridge.PROP_CONTROL_DIR);
        System.clearProperty("oscar.sandbox.rapiscanPort1");
        System.clearProperty("oscar.sandbox.rapiscanPort2");
        System.clearProperty("oscar.sandbox.skipRtsp");
        System.clearProperty("oscar.sandbox.skipAutoStage");
        if (tempRoot != null && Files.exists(tempRoot)) {
            Files.walk(tempRoot)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    public void operationalNodeBlocksSandboxEndpointsWhileExposingAdminHostLauncher() throws Exception {
        tempRoot = Files.createTempDirectory("oscar-sandbox-prod-test-");
        Path controlDir = tempRoot.resolve("sandbox-control");
        Files.createDirectories(controlDir);
        System.setProperty(TrainingSandboxEnvironment.PROP_SANDBOX_MODE, "false");
        System.setProperty(SandboxHostControlBridge.PROP_CONTROL_DIR, controlDir.toString());

        ISensorHub hub = buildMockHub(Map.of(
                "admin", List.of("admin"),
                "operator1", List.of("operator")
        ));
        var logger = LoggerFactory.getLogger(TrainingSandboxIntegrationTest.class);
        OSCARServiceConfig cfg = new OSCARServiceConfig();
        RuntimeExtensionsManager extMgr = new RuntimeExtensionsManager(
                hub,
                cfg,
                new EmlPluginHandler(hub, logger, cfg, tempRoot.resolve("eml")),
                logger,
                tempRoot.resolve("ext"),
                tempRoot.resolve("maps"),
                () -> {});

        try (TrainingSandboxManager manager = new TrainingSandboxManager(hub, null, extMgr, logger)) {
            manager.start();
            TrainingSandboxServlet servlet = new TrainingSandboxServlet(hub, manager, logger);

            // 1. Operational mode returns fast {"sandboxMode": false}
            MockResp active = invoke(servlet, "GET", "/active", "operator1", Map.of(), null);
            assertEquals(200, active.status);
            JsonObject activeJson = JsonParser.parseString(active.bodyString()).getAsJsonObject();
            assertFalse(activeJson.get("sandboxMode").getAsBoolean());

            // 2. Simulation endpoints strictly blocked outside sandbox mode
            MockResp blockedInject = invoke(
                    servlet,
                    "POST",
                    "/inject",
                    "admin",
                    Map.of("X-Requested-With", "XMLHttpRequest"),
                    "{\"scenario\":\"gamma_alarm\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(403, blockedInject.status);

            MockResp blockedSample = invoke(servlet, "GET", "/samples/tutorial-1-lane.csv", "admin", Map.of(), null);
            assertEquals(403, blockedSample.status);

            // 3. Host bridge enforces Admin RBAC and queues start requests when host agent status is active
            MockResp opHost = invoke(servlet, "GET", "/host", "operator1", Map.of(), null);
            assertEquals(403, opHost.status);

            MockResp adminHostNoAgent = invoke(servlet, "GET", "/host", "admin", Map.of(), null);
            assertEquals(200, adminHostNoAgent.status);
            JsonObject hostInit = JsonParser.parseString(adminHostNoAgent.bodyString()).getAsJsonObject();
            assertFalse(hostInit.get("agentActive").getAsBoolean());
            assertTrue(hostInit.get("linuxStartCommand").getAsString().contains("oscar.sh sandbox start"));

            Files.writeString(
                    controlDir.resolve("status.json"),
                    "{\"agentActive\":true,\"sandboxRunning\":false,\"httpsPort\":8444,\"ttlMinutes\":120}",
                    StandardCharsets.UTF_8);
            MockResp startReq = invoke(
                    servlet,
                    "POST",
                    "/host",
                    "admin",
                    Map.of("X-Requested-With", "XMLHttpRequest"),
                    "{\"action\":\"start\",\"httpsPort\":8444,\"ttlMinutes\":90}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200, startReq.status);
            assertTrue(Files.isRegularFile(controlDir.resolve("request.json")));
            String queued = Files.readString(controlDir.resolve("request.json"), StandardCharsets.UTF_8);
            assertTrue(queued.contains("\"action\":\"start\""));
        }
    }

    @Test
    public void sampleAssetsValidateAgainstSpreadsheetPmTilesAndWcoUff3Parsers() throws Exception {
        tempRoot = Files.createTempDirectory("oscar-sandbox-samples-test-");

        assertEquals(11, TrainingSandboxEnvironment.CONTAINER_1.length());
        assertEquals(11, TrainingSandboxEnvironment.CONTAINER_2.length());

        // 1. Tutorial 1-lane CSV parses cleanly with archiveEnabled=false
        SandboxSampleAssets.SamplePayload csvPayload = SandboxSampleAssets.buildSample(
                SandboxSampleAssets.FILE_TUTORIAL_CSV, 10099, 8554);
        assertNotNull(csvPayload);
        SpreadsheetParser parser = new SpreadsheetParser();
        List<LaneConfig> parsedLanes = parser.deserialize(new String(csvPayload.bytes(), StandardCharsets.UTF_8));
        assertEquals(1, parsedLanes.size());
        assertEquals("DemoLane01", parsedLanes.get(0).name);
        assertTrue(parsedLanes.get(0).name.length() <= 12);
        assertFalse(parser.getRetentionPolicyConfig().archiveEnabled);

        // 1b. JNI H.264 synthetic container MP4 encodes cleanly with zero external CLI ffmpeg binary
        Path testMp4 = tempRoot.resolve("jni-container-test.mp4");
        SandboxRtspCameraStreamer.encodeSyntheticContainerMp4(
                testMp4,
                TrainingSandboxEnvironment.CONTAINER_1,
                TrainingSandboxEnvironment.PLATE_1,
                "DEMOLANE01 · NORTH GATE",
                new java.awt.Color(20, 48, 92));
        assertTrue(Files.isRegularFile(testMp4));
        assertTrue("Expected non-trivial H.264 MP4 payload", Files.size(testMp4) > 8_192L);

        // 2. Minimal PMTiles v3 archive passes strict header & bounds validation
        SandboxSampleAssets.SamplePayload pmPayload = SandboxSampleAssets.buildSample(
                SandboxSampleAssets.FILE_SAMPLE_PMTILES, 10099, 8554);
        assertNotNull(pmPayload);
        PmTilesHeader header = PmTilesHeader.parseHeaderBytes(pmPayload.bytes(), pmPayload.bytes().length);
        assertEquals(3, header.specVersion());
        assertEquals("png", header.tileType());

        // 3. WCO UFF 3.0 archive parses via UffZipReader + WcoUffParser with X-ray + HEMD + OCR + Annotation
        Path uffDir = SandboxSampleAssets.populateLocalUffSampleShare(tempRoot);
        Path exactUff = uffDir.resolve(SandboxSampleAssets.UFF_UUID_EXACT + ".uff");
        assertTrue(Files.isRegularFile(exactUff));
        try (UffSeekableSource src = UffSeekableSource.fromLocalPath(exactUff)) {
            UffZipReader zip = UffZipReader.open(src);
            WcoUffModels.UffCaseMetadata meta = WcoUffParser.parseArchiveMetadata(
                    zip, exactUff.getFileName().toString(), exactUff.getFileName().toString(), Files.size(exactUff), System.currentTimeMillis());
            assertEquals(SandboxSampleAssets.UFF_UUID_EXACT, meta.uffUuid());
            assertEquals(1, meta.xrayFiles().size());
            assertFalse(meta.xrayFiles().get(0).hemdEntryPath().isEmpty());
            assertEquals(4, meta.xrayFiles().get(0).zMaps().size());
            assertEquals(1, meta.annotations().size());
            assertTrue(meta.allNormalizedVehicleIds().contains(TrainingSandboxEnvironment.CONTAINER_1));
        }
    }

    @Test
    public void sandboxModeStreamsRapiscanClassifyNormAndGeneratesWatermarkedCompletionPdf() throws Exception {
        tempRoot = Files.createTempDirectory("oscar-sandbox-active-test-");
        System.setProperty(TrainingSandboxEnvironment.PROP_SANDBOX_MODE, "true");
        System.setProperty(TrainingSandboxManager.PROP_WORK_DIR, tempRoot.resolve("work").toString());
        System.setProperty("oscar.sandbox.rapiscanPort1", "0");
        System.setProperty("oscar.sandbox.rapiscanPort2", "0");
        System.setProperty("oscar.sandbox.skipRtsp", "true");
        System.setProperty("oscar.sandbox.skipAutoStage", "true");

        ISensorHub hub = buildMockHub(Map.of("operator1", List.of("operator")));
        var logger = LoggerFactory.getLogger(TrainingSandboxIntegrationTest.class);
        OSCARServiceConfig cfg = new OSCARServiceConfig();
        cfg.nodeId = "sandbox-test-node";
        RuntimeExtensionsManager extMgr = new RuntimeExtensionsManager(
                hub,
                cfg,
                new EmlPluginHandler(hub, logger, cfg, tempRoot.resolve("eml")),
                logger,
                tempRoot.resolve("ext"),
                tempRoot.resolve("maps"),
                () -> {});

        try (TrainingSandboxManager manager = new TrainingSandboxManager(hub, null, extMgr, logger)) {
            manager.start();
            TrainingSandboxServlet servlet = new TrainingSandboxServlet(hub, manager, logger);

            // 1. Connect TCP client to loopback Rapiscan #1 and verify handshake + gamma_alarm injection
            int port = manager.getRapiscanPort1();
            assertTrue(port > 0);
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(5_000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                List<String> initial = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    initial.add(reader.readLine());
                }
                assertTrue(initial.stream().anyMatch(l -> l != null && l.startsWith("SG1,")));
                assertTrue(initial.stream().anyMatch(l -> l != null && l.startsWith("SN1,")));

                MockResp injectResp = invoke(
                        servlet,
                        "POST",
                        "/inject",
                        "operator1",
                        Map.of("X-Requested-With", "XMLHttpRequest"),
                        "{\"scenario\":\"gamma_alarm\",\"laneSlot\":1}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200, injectResp.status);

                List<String> occupancyLines = new ArrayList<>();
                long deadline = System.currentTimeMillis() + 6_000L;
                while (System.currentTimeMillis() < deadline) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    occupancyLines.add(line);
                    if (line.startsWith("GX,")) {
                        break;
                    }
                }
                assertTrue(occupancyLines.stream().anyMatch(l -> l.startsWith("GA,")));
                assertTrue(occupancyLines.stream().anyMatch(l -> l.startsWith("GX,")));

                // Verify SandboxEmlTrainingAlgorithm keeps asymmetric gamma_alarm as INVESTIGATE,
                // while uniform 4-panel NORM cargo auto-releases as RELEASE (Code 9999)
                SandboxEmlTrainingAlgorithm eml = new SandboxEmlTrainingAlgorithm();
                EmlLaneParameters params = new EmlLaneParameters("DemoLane01", 1, true, 4.8, 5, 1);
                EmlEvaluationResult threatEval = eml.evaluate(params, occupancyLines);
                assertNotNull(threatEval);
                assertFalse(threatEval.isAutoReleaseNonThreat());

                List<String> normLines = List.of(
                        "NB,1,0,1,0",
                        "GB,186,190,188,186",
                        "GS,39,40,39,38",
                        "GA,128,132,130,126",
                        "GA,129,131,130,127",
                        "GA,130,132,129,128",
                        "GA,128,131,130,127",
                        "GA,127,130,129,126",
                        "GA,128,131,130,128",
                        "GX,102,1000");
                EmlEvaluationResult normEval = eml.evaluate(params, normLines);
                assertNotNull(normEval);
                assertTrue(normEval.isAutoReleaseNonThreat());
            }

            // 2. Render 1-page PDF Training Completion Certificate and verify watermark text
            String certBody = """
                    {
                      "traineeName": "Alex Gatehouse",
                      "completedLabs": [
                        {"trackId": "operator", "labId": "op-1", "title": "Priority Queue & 5-Frame Adjudication", "status": "verified"},
                        {"trackId": "operator", "labId": "op-2", "title": "NORM Code 9999 vs Neutron Safety", "status": "verified"}
                      ]
                    }
                    """;
            MockResp certResp = invoke(
                    servlet,
                    "POST",
                    "/certificate",
                    "operator1",
                    Map.of("X-Requested-With", "XMLHttpRequest"),
                    certBody.getBytes(StandardCharsets.UTF_8));
            assertEquals(200, certResp.status);
            assertEquals("application/pdf", certResp.contentType);
            byte[] pdfBytes = certResp.body.toByteArray();
            assertTrue(pdfBytes.length > 1000);
            try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
                assertEquals(1, doc.getNumberOfPages());
                String text = new PDFTextStripper().getText(doc);
                assertTrue(text.contains("TRAINING SANDBOX"));
                assertTrue(text.contains("Alex Gatehouse"));
                assertTrue(text.contains("Priority Queue"));
            }
        }
    }

    private static ISensorHub buildMockHub(Map<String, List<String>> userRoles) {
        ISecurityManager sec = (ISecurityManager) Proxy.newProxyInstance(
                TrainingSandboxIntegrationTest.class.getClassLoader(),
                new Class<?>[]{ISecurityManager.class},
                (proxy, method, args) -> {
                    if ("getUserInfo".equals(method.getName()) && args != null && args.length == 1) {
                        String u = (String) args[0];
                        List<String> roles = userRoles.get(u);
                        if (roles == null) {
                            return null;
                        }
                        return buildUserInfo(u, roles);
                    }
                    return null;
                });

        return (ISensorHub) Proxy.newProxyInstance(
                TrainingSandboxIntegrationTest.class.getClassLoader(),
                new Class<?>[]{ISensorHub.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSecurityManager" -> sec;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "MockSensorHub";
                    default -> null;
                });
    }

    private static IUserInfo buildUserInfo(String username, Collection<String> roles) {
        return (IUserInfo) Proxy.newProxyInstance(
                TrainingSandboxIntegrationTest.class.getClassLoader(),
                new Class<?>[]{IUserInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId", "getName" -> username;
                    case "getRoles" -> roles;
                    default -> null;
                });
    }

    private static final class MockResp {
        int status = 200;
        String contentType;
        final Map<String, String> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        String bodyString() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    private static MockResp invoke(
            TrainingSandboxServlet servlet,
            String method,
            String pathInfo,
            String remoteUser,
            Map<String, String> headers,
            byte[] bodyBytes
    ) throws Exception {
        byte[] payload = bodyBytes == null ? new byte[0] : bodyBytes;
        MockResp mockResp = new MockResp();

        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                TrainingSandboxIntegrationTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getMethod" -> method;
                    case "getPathInfo" -> pathInfo;
                    case "getContextPath" -> "/sensorhub";
                    case "getServletPath" -> "/oscar/sandbox";
                    case "getRemoteUser" -> remoteUser;
                    case "getUserPrincipal" -> remoteUser == null ? null : (Principal) () -> remoteUser;
                    case "isUserInRole" -> "admin".equalsIgnoreCase(remoteUser) && "admin".equals(args[0]);
                    case "getHeader" -> {
                        String key = (String) args[0];
                        for (var e : headers.entrySet()) {
                            if (e.getKey().equalsIgnoreCase(key)) {
                                yield e.getValue();
                            }
                        }
                        yield null;
                    }
                    case "getInputStream" -> new ServletInputStream() {
                        private final ByteArrayInputStream in = new ByteArrayInputStream(payload);
                        @Override public boolean isFinished() { return in.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener readListener) {}
                        @Override public int read() { return in.read(); }
                        @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                    };
                    case "getContentLength" -> payload.length;
                    case "getContentLengthLong" -> (long) payload.length;
                    default -> null;
                });

        StringWriter writerBuf = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writerBuf);
        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(
                TrainingSandboxIntegrationTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "setStatus" -> { mockResp.status = (Integer) args[0]; yield null; }
                    case "getStatus" -> mockResp.status;
                    case "sendError" -> { mockResp.status = (Integer) args[0]; yield null; }
                    case "setContentType" -> { mockResp.contentType = (String) args[0]; yield null; }
                    case "getContentType" -> mockResp.contentType;
                    case "setHeader", "addHeader" -> { mockResp.headers.put((String) args[0], (String) args[1]); yield null; }
                    case "getHeader" -> mockResp.headers.get((String) args[0]);
                    case "setContentLength", "setContentLengthLong" -> null;
                    case "isCommitted" -> false;
                    case "getOutputStream" -> new ServletOutputStream() {
                        @Override public boolean isReady() { return true; }
                        @Override public void setWriteListener(WriteListener writeListener) {}
                        @Override public void write(int b) { mockResp.body.write(b); }
                        @Override public void write(byte[] b, int off, int len) { mockResp.body.write(b, off, len); }
                    };
                    case "getWriter" -> printWriter;
                    default -> null;
                });

        servlet.service(req, resp);
        printWriter.flush();
        if (mockResp.body.size() == 0 && writerBuf.getBuffer().length() > 0) {
            mockResp.body.write(writerBuf.toString().getBytes(StandardCharsets.UTF_8));
        }
        return mockResp;
    }
}
