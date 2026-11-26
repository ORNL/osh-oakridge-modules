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
import com.botts.impl.system.lane.config.RS350RPMConfig;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient;
import com.botts.impl.system.lane.mobile.MobileDetectorDescriptor;
import com.botts.impl.system.lane.mobile.MobileDetectorSystem;
import com.botts.impl.system.lane.mobile.config.MobileDetectorConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.After;
import org.junit.Test;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.security.IUserInfo;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.eml.EmlEvaluationResult;
import org.sensorhub.impl.utils.rad.eml.EmlLaneParameters;
import org.slf4j.LoggerFactory;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        System.clearProperty("oscar.sandbox.webRelayPort1");
        System.clearProperty("oscar.sandbox.webRelayPort2");
        System.clearProperty("oscar.sandbox.rs350Port");
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

            // 3. Host bridge allows authenticated status + idempotent start, while reserving stop/reset for Admin
            MockResp unauthHost = invoke(servlet, "GET", "/host", null, Map.of(), null);
            assertEquals(401, unauthHost.status);

            MockResp opHost = invoke(servlet, "GET", "/host", "operator1", Map.of(), null);
            assertEquals(200, opHost.status);
            JsonObject opHostJson = JsonParser.parseString(opHost.bodyString()).getAsJsonObject();
            assertFalse(opHostJson.get("canStopOrReset").getAsBoolean());

            MockResp adminHostNoAgent = invoke(servlet, "GET", "/host", "admin", Map.of(), null);
            assertEquals(200, adminHostNoAgent.status);
            JsonObject hostInit = JsonParser.parseString(adminHostNoAgent.bodyString()).getAsJsonObject();
            assertFalse(hostInit.get("agentActive").getAsBoolean());
            assertTrue(hostInit.get("canStopOrReset").getAsBoolean());
            assertTrue(hostInit.get("linuxStartCommand").getAsString().contains("oscar.sh sandbox start"));

            Files.writeString(
                    controlDir.resolve("status.json"),
                    "{\"agentActive\":true,\"sandboxRunning\":false,\"httpsPort\":8444,\"ttlMinutes\":120}",
                    StandardCharsets.UTF_8);

            // Operator can start (TTL clamped to 120m), but cannot stop or reset
            MockResp opStartReq = invoke(
                    servlet,
                    "POST",
                    "/host",
                    "operator1",
                    Map.of("X-Requested-With", "XMLHttpRequest"),
                    "{\"action\":\"start\",\"httpsPort\":9444,\"ttlMinutes\":300}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200, opStartReq.status);
            assertTrue(Files.isRegularFile(controlDir.resolve("request.json")));
            String queuedOp = Files.readString(controlDir.resolve("request.json"), StandardCharsets.UTF_8);
            assertTrue(queuedOp.contains("\"action\":\"start\""));
            assertTrue(queuedOp.contains("\"httpsPort\":8444"));
            assertTrue(queuedOp.contains("\"ttlMinutes\":120"));

            Files.deleteIfExists(controlDir.resolve("request.json"));
            Files.writeString(
                    controlDir.resolve("status.json"),
                    "{\"agentActive\":true,\"sandboxRunning\":true,\"httpsPort\":8444,\"ttlMinutes\":120,\"expiresAt\":\"2026-11-22T02:00:00Z\"}",
                    StandardCharsets.UTF_8);

            // Idempotent start when already running does NOT overwrite request.json or reset active work
            MockResp noopStart = invoke(
                    servlet,
                    "POST",
                    "/host",
                    "operator1",
                    Map.of("X-Requested-With", "XMLHttpRequest"),
                    "{\"action\":\"start\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200, noopStart.status);
            assertFalse(Files.exists(controlDir.resolve("request.json")));
            JsonObject noopJson = JsonParser.parseString(noopStart.bodyString()).getAsJsonObject();
            assertEquals("noop-already-running", noopJson.getAsJsonObject("activeJob").get("requestId").getAsString());

            MockResp opStopBlocked = invoke(
                    servlet,
                    "POST",
                    "/host",
                    "operator1",
                    Map.of("X-Requested-With", "XMLHttpRequest"),
                    "{\"action\":\"stop\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(403, opStopBlocked.status);

            MockResp opResetBlocked = invoke(
                    servlet,
                    "POST",
                    "/host",
                    "operator1",
                    Map.of("X-Requested-With", "XMLHttpRequest"),
                    "{\"action\":\"reset\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(403, opResetBlocked.status);

            MockResp adminStopReq = invoke(
                    servlet,
                    "POST",
                    "/host",
                    "admin",
                    Map.of("X-Requested-With", "XMLHttpRequest"),
                    "{\"action\":\"stop\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200, adminStopReq.status);
            assertTrue(Files.isRegularFile(controlDir.resolve("request.json")));
            String queuedStop = Files.readString(controlDir.resolve("request.json"), StandardCharsets.UTF_8);
            assertTrue(queuedStop.contains("\"action\":\"stop\""));
        }
    }

    @Test
    public void sampleAssetsValidateAgainstSpreadsheetPmTilesAndWcoUff3Parsers() throws Exception {
        tempRoot = Files.createTempDirectory("oscar-sandbox-samples-test-");

        assertEquals("SCFU8572724", TrainingSandboxEnvironment.CONTAINER_1);
        assertEquals("RSTU2402941", TrainingSandboxEnvironment.CONTAINER_2);
        assertEquals("SWT03", TrainingSandboxEnvironment.PLATE_1);
        assertEquals("M50M1", TrainingSandboxEnvironment.PLATE_2);

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

        // 1b. JNI H.264 synthetic moving tractor-trailer MP4 encodes cleanly with zero external CLI ffmpeg binary
        Path testMp4 = tempRoot.resolve("jni-container-test.mp4");
        SandboxRtspCameraStreamer.encodeSyntheticContainerMp4(
                testMp4,
                TrainingSandboxEnvironment.CONTAINER_1,
                TrainingSandboxEnvironment.PLATE_1,
                "DEMOLANE01 · NORTH GATE",
                SandboxRtspCameraStreamer.LANE1_CONTAINER_COLOR);
        assertTrue(Files.isRegularFile(testMp4));
        assertTrue("Expected non-trivial 75-frame H.264 MP4 payload", Files.size(testMp4) > 24_576L);

        // 2. Minimal PMTiles v3 archive passes strict header & bounds validation
        SandboxSampleAssets.SamplePayload pmPayload = SandboxSampleAssets.buildSample(
                SandboxSampleAssets.FILE_SAMPLE_PMTILES, 10099, 8554);
        assertNotNull(pmPayload);
        PmTilesHeader header = PmTilesHeader.parseHeaderBytes(pmPayload.bytes(), pmPayload.bytes().length);
        assertEquals(3, header.specVersion());
        assertEquals("png", header.tileType());

        // 3. WCO UFF 3.0 full-truck archive parses via UffZipReader + WcoUffParser with X-ray + HEMD + Manifest + N42 + OCR + Annotation
        Path uffDir = SandboxSampleAssets.populateLocalUffSampleShare(tempRoot);
        Path exactUff = uffDir.resolve(SandboxSampleAssets.UFF_UUID_EXACT + ".uff");
        Path normUff = uffDir.resolve(SandboxSampleAssets.UFF_UUID_NORM + ".uff");
        Path fuzzyUff = uffDir.resolve(SandboxSampleAssets.UFF_UUID_FUZZY + ".uff");
        assertTrue(Files.isRegularFile(exactUff));
        assertTrue(Files.isRegularFile(normUff));
        assertTrue(Files.isRegularFile(fuzzyUff));
        try (UffSeekableSource src = UffSeekableSource.fromLocalPath(exactUff)) {
            UffZipReader zip = UffZipReader.open(src);
            WcoUffModels.UffCaseMetadata meta = WcoUffParser.parseArchiveMetadata(
                    zip, exactUff.getFileName().toString(), exactUff.getFileName().toString(), Files.size(exactUff), System.currentTimeMillis());
            assertEquals(SandboxSampleAssets.UFF_UUID_EXACT, meta.uffUuid());
            assertEquals("3.0", meta.uffVersion());
            assertFalse("Expected embedded Trade Manifest PDF", meta.manifestEntryPath().isEmpty());
            assertEquals(1, meta.radiationFiles().size());
            assertEquals(2, meta.ocrResults().size());
            assertEquals(1, meta.xrayFiles().size());
            assertFalse(meta.xrayFiles().get(0).hemdEntryPath().isEmpty());
            assertEquals(4, meta.xrayFiles().get(0).zMaps().size());
            assertEquals(1, meta.annotations().size());
            assertTrue("Expected normalized [0..1] annotation bounds", meta.annotations().get(0).left() > 0.70 && meta.annotations().get(0).right() < 0.85);
            assertTrue(meta.allNormalizedVehicleIds().contains(TrainingSandboxEnvironment.CONTAINER_1));
            assertTrue(meta.allNormalizedVehicleIds().contains(TrainingSandboxEnvironment.PLATE_1));
        }

        String qaDirProp = System.getProperty("oscar.sandbox.exportQaDir");
        if (qaDirProp != null && !qaDirProp.isBlank()) {
            Path qaDir = java.nio.file.Paths.get(qaDirProp);
            Files.createDirectories(qaDir);
            Files.copy(testMp4, qaDir.resolve("lane1-transit-scfu8572724.mp4"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(exactUff, qaDir.resolve("sample-cargo-uff3-scfu8572724.uff"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    public void sandboxModeStreamsRapiscanClassifyNormAndGeneratesWatermarkedCompletionPdf() throws Exception {
        tempRoot = Files.createTempDirectory("oscar-sandbox-active-test-");
        System.setProperty(TrainingSandboxEnvironment.PROP_SANDBOX_MODE, "true");
        System.setProperty(TrainingSandboxManager.PROP_WORK_DIR, tempRoot.resolve("work").toString());
        System.setProperty("oscar.sandbox.rapiscanPort1", "0");
        System.setProperty("oscar.sandbox.rapiscanPort2", "0");
        System.setProperty("oscar.sandbox.webRelayPort1", "0");
        System.setProperty("oscar.sandbox.webRelayPort2", "0");
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

                // Verify fault_tamper + clear_faults broadcasts TT followed by TC + clean GB/NB restoration
                MockResp tamperResp = invoke(
                        servlet,
                        "POST",
                        "/inject",
                        "operator1",
                        Map.of("X-Requested-With", "XMLHttpRequest"),
                        "{\"scenario\":\"fault_tamper\",\"laneSlot\":1}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200, tamperResp.status);

                MockResp clearResp = invoke(
                        servlet,
                        "POST",
                        "/inject",
                        "operator1",
                        Map.of("X-Requested-With", "XMLHttpRequest"),
                        "{\"scenario\":\"clear_faults\",\"laneSlot\":1}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200, clearResp.status);

                List<String> faultLines = new ArrayList<>();
                long clearDeadline = System.currentTimeMillis() + 2_500L;
                int clearConfirmations = 0;
                while (System.currentTimeMillis() < clearDeadline && clearConfirmations < 2) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    faultLines.add(line);
                    if ("TC".equals(line.trim())) {
                        clearConfirmations++;
                    }
                }
                assertTrue(faultLines.contains("TT"));
                assertTrue("Expected initial + confirmation TC frames on clear_faults", clearConfirmations >= 2);
                assertTrue(faultLines.stream().anyMatch(l -> l.startsWith("GB,186,190,188,186")));

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

            // 1b. Verify SandboxWebRelayLoopbackServer responds to WebRelayQuadClient (Lab 3.5)
            int wrPort = manager.getWebRelayPort1();
            assertTrue(wrPort > 0);
            WebRelayQuadClient wrClient = new WebRelayQuadClient();
            WebRelayQuadClient.EndpointSpec wrEndpoint = new WebRelayQuadClient.EndpointSpec("127.0.0.1", wrPort, "", "");
            var initSnap = wrClient.readState(wrEndpoint);
            assertTrue(initSnap.online());
            assertTrue(initSnap.isPassThrough());
            var armedSnap = wrClient.setArmedIntercept(wrEndpoint);
            assertTrue(armedSnap.isArmedIntercept());
            var pulseSnap = wrClient.pulseIndicators(wrEndpoint, true, false, 1);
            assertTrue(pulseSnap.isGammaIndicatorActive());
            assertFalse(pulseSnap.isNeutronIndicatorActive());
            var passSnap = wrClient.setPassThrough(wrEndpoint);
            assertTrue(passSnap.isPassThrough());

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

    @Test
    public void sandboxRs350LoopbackStreamsValidStxEtxN42FramesAndReportsMobileDetectorConfigured() throws Exception {
        tempRoot = Files.createTempDirectory("oscar-sandbox-rs350-test-");
        var logger = LoggerFactory.getLogger(TrainingSandboxIntegrationTest.class);

        // 1. Strictly inert outside OSCAR_SANDBOX_MODE=true (never binds any port)
        System.setProperty(TrainingSandboxEnvironment.PROP_SANDBOX_MODE, "false");
        try (SandboxRs350LoopbackServer inert = new SandboxRs350LoopbackServer(0, "InertWalker", logger)) {
            inert.start();
            assertFalse("Expected RS-350 loopback server to stay inert outside sandbox mode", inert.isRunning());
        }

        // 2. Occupy a loopback port to verify collision-safe ephemeral port fallback
        try (ServerSocket occupiedSocket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            int occupiedPort = occupiedSocket.getLocalPort();
            System.setProperty(TrainingSandboxEnvironment.PROP_SANDBOX_MODE, "true");
            System.setProperty(TrainingSandboxManager.PROP_WORK_DIR, tempRoot.resolve("work").toString());
            System.setProperty("oscar.sandbox.rapiscanPort1", "0");
            System.setProperty("oscar.sandbox.rapiscanPort2", "0");
            System.setProperty("oscar.sandbox.webRelayPort1", "0");
            System.setProperty("oscar.sandbox.webRelayPort2", "0");
            System.setProperty("oscar.sandbox.rs350Port", String.valueOf(occupiedPort));
            System.setProperty("oscar.sandbox.skipRtsp", "true");
            System.setProperty("oscar.sandbox.skipAutoStage", "true");

            SensorHub realHub = new SensorHub();
            realHub.start();
            try {
                OSCARServiceConfig cfg = new OSCARServiceConfig();
                cfg.nodeId = "sandbox-rs350-node";
                RuntimeExtensionsManager extMgr = new RuntimeExtensionsManager(
                        realHub,
                        cfg,
                        new EmlPluginHandler(realHub, logger, cfg, tempRoot.resolve("eml")),
                        logger,
                        tempRoot.resolve("ext"),
                        tempRoot.resolve("maps"),
                        () -> {});

                try (TrainingSandboxManager manager = new TrainingSandboxManager(realHub, null, extMgr, logger)) {
                    manager.start();
                    TrainingSandboxServlet servlet = new TrainingSandboxServlet(realHub, manager, logger);

                    int rs350Port = manager.getRs350Port();
                    assertTrue(rs350Port > 0);
                    assertNotEquals("Expected fallback port when requested port is busy", occupiedPort, rs350Port);

                    // 3. Read live STX..ETX frames over TCP and unmarshal via RADHelper + namespace-aware DOM
                    RADHelper radHelper = new RADHelper();
                    try (Socket sock = new Socket("127.0.0.1", rs350Port)) {
                        sock.setSoTimeout(5_000);
                        InputStream in = sock.getInputStream();

                        String frame1Xml = readSingleStxEtxFrame(in);
                        String frame2Xml = readSingleStxEtxFrame(in);
                        assertNotNull(radHelper.getRadInstrumentData(frame1Xml));
                        assertNotNull(radHelper.getRadInstrumentData(frame2Xml));

                        Document dom1 = parseXmlDom(frame1Xml);
                        Document dom2 = parseXmlDom(frame2Xml);
                        assertEquals(SandboxRs350LoopbackServer.N42_NAMESPACE, dom1.getDocumentElement().getNamespaceURI());
                        assertEquals("WALKER1", firstTagText(dom1, "RadInstrumentIdentifier"));
                        assertEquals("RS-350", firstTagText(dom1, "RadInstrumentModelName"));

                        NodeList spectra1 = dom1.getElementsByTagNameNS(SandboxRs350LoopbackServer.N42_NAMESPACE, "Spectrum");
                        assertTrue("Initial bootstrap frame must carry Background + Foreground spectra", spectra1.getLength() >= 4);
                        String fgLinChannels = firstSpectrumChannelDataById(dom1, "FgLin");
                        String fgCmpChannels = firstSpectrumChannelDataById(dom1, "FgCmp");
                        assertEquals(1024, fgLinChannels.trim().split("\\s+").length);
                        assertEquals(256, fgCmpChannels.trim().split("\\s+").length);

                        double lat1 = Double.parseDouble(firstTagText(dom1, "LatitudeValue"));
                        double lon1 = Double.parseDouble(firstTagText(dom1, "LongitudeValue"));
                        double lat2 = Double.parseDouble(firstTagText(dom2, "LatitudeValue"));
                        double lon2 = Double.parseDouble(firstTagText(dom2, "LongitudeValue"));
                        assertTrue(lat1 > 35.92);
                        assertTrue(lon1 < -84.30);
                        boolean moved = Math.abs(lat2 - lat1) > 1e-6 || Math.abs(lon2 - lon1) > 1e-6;
                        assertTrue("Expected walker GPS position to advance across 1 Hz frames", moved);
                    }

                    // 4. Verify Cs-137 hotspot frame emits <DerivedData> + <AnalysisResults><RadAlarm> and unmarshals cleanly
                    SandboxRs350LoopbackServer walkerServer = manager.getRs350LoopbackServer();
                    assertNotNull(walkerServer);
                    String hotspotXml = walkerServer.buildDocumentXml(
                            SandboxRs350LoopbackServer.HOTSPOT_LAT,
                            SandboxRs350LoopbackServer.HOTSPOT_LON,
                            96.0,
                            false,
                            Instant.parse("2026-10-18T12:00:05Z"));
                    assertNotNull(radHelper.getRadInstrumentData(hotspotXml));
                    Document alarmDom = parseXmlDom(hotspotXml);
                    assertEquals("Alarm", firstTagText(alarmDom, "Remark"));
                    assertEquals("Gamma", firstTagText(alarmDom, "RadAlarmCategoryCode"));
                    assertEquals("Cs-137", firstTagText(alarmDom, "RadAlarmDescription"));

                    // 5. Verify mobileDetectorConfigured is false before adding/starting MobileDetectorSystem,
                    // remains false when loaded unstarted, and becomes true once MobileDetectorSystem is started
                    MockResp beforeResp = invoke(servlet, "GET", "/active", "admin", Map.of(), null);
                    assertEquals(200, beforeResp.status);
                    JsonObject beforeVer = JsonParser.parseString(beforeResp.bodyString())
                            .getAsJsonObject()
                            .getAsJsonObject("verification");
                    assertFalse(beforeVer.get("mobileDetectorConfigured").getAsBoolean());
                    assertEquals(0, beforeVer.get("mobileDetectorCount").getAsInt());

                    var reg = realHub.getModuleRegistry();
                    MobileDetectorConfig mobileCfg = (MobileDetectorConfig) reg.createModuleConfig(new MobileDetectorDescriptor());
                    mobileCfg.id = UUID.randomUUID().toString();
                    mobileCfg.name = "PatrolRS350";
                    mobileCfg.uniqueID = "patrol-rs350";
                    mobileCfg.autoStart = false;
                    RS350RPMConfig rs350Cfg = new RS350RPMConfig();
                    rs350Cfg.remoteHost = "127.0.0.1";
                    rs350Cfg.remotePort = rs350Port;
                    mobileCfg.detectorConfig = rs350Cfg;

                    MobileDetectorSystem mobile = (MobileDetectorSystem) reg.loadModule(mobileCfg);
                    MockResp loadedNotStartedResp = invoke(servlet, "GET", "/active", "admin", Map.of(), null);
                    JsonObject loadedVer = JsonParser.parseString(loadedNotStartedResp.bodyString())
                            .getAsJsonObject()
                            .getAsJsonObject("verification");
                    assertFalse("Unstarted MobileDetectorSystem must not prematurely verify lab",
                            loadedVer.get("mobileDetectorConfigured").getAsBoolean());
                    assertEquals(1, loadedVer.get("mobileDetectorCount").getAsInt());

                    reg.startModule(mobileCfg.id);
                    assertTrue(mobile.waitForState(ModuleEvent.ModuleState.STARTED, 10_000L));

                    MockResp startedResp = invoke(servlet, "GET", "/active", "admin", Map.of(), null);
                    JsonObject startedJson = JsonParser.parseString(startedResp.bodyString()).getAsJsonObject();
                    JsonObject startedVer = startedJson.getAsJsonObject("verification");
                    assertTrue("Started MobileDetectorSystem must report mobileDetectorConfigured=true",
                            startedVer.get("mobileDetectorConfigured").getAsBoolean());
                    assertEquals(1, startedVer.get("onlineMobileDetectorCount").getAsInt());
                    assertEquals(rs350Port, startedJson.getAsJsonObject("loopbackEndpoints").get("rs350Port").getAsInt());
                }
            } finally {
                realHub.stop();
            }
        }
    }

    private static String readSingleStxEtxFrame(InputStream in) throws IOException {
        int ch;
        do {
            ch = in.read();
            if (ch == -1) {
                throw new IOException("EOF before STX (0x02)");
            }
        } while (ch != SandboxRs350LoopbackServer.STX);

        ByteArrayOutputStream buf = new ByteArrayOutputStream(8_192);
        while (true) {
            ch = in.read();
            if (ch == -1) {
                throw new IOException("EOF before ETX (0x03)");
            }
            if (ch == SandboxRs350LoopbackServer.ETX) {
                break;
            }
            buf.write(ch);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static Document parseXmlDom(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String firstTagText(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS(SandboxRs350LoopbackServer.N42_NAMESPACE, localName);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }

    private static String firstSpectrumChannelDataById(Document doc, String spectrumId) {
        NodeList nodes = doc.getElementsByTagNameNS(SandboxRs350LoopbackServer.N42_NAMESPACE, "Spectrum");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (spectrumId.equals(el.getAttribute("id"))) {
                NodeList ch = el.getElementsByTagNameNS(SandboxRs350LoopbackServer.N42_NAMESPACE, "ChannelData");
                if (ch.getLength() > 0) {
                    return ch.item(0).getTextContent();
                }
            }
        }
        return "";
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
