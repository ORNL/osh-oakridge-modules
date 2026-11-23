/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import com.botts.impl.service.oscar.Constants;
import com.botts.impl.service.oscar.OSCARServiceModule;
import com.botts.impl.service.oscar.extensions.RuntimeExtensionsManager;
import com.botts.impl.service.oscar.reports.helpers.PdfDocumentBuilder;
import com.botts.impl.service.oscar.reports.helpers.TableGenerator;
import com.botts.impl.service.oscar.vehicle.VehicleIncidentAggregator;
import com.botts.impl.system.lane.AdjudicationControl;
import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.CustomCameraConfig;
import com.botts.impl.system.lane.config.LaneConfig;
import com.botts.impl.system.lane.config.LaneOptionsConfig;
import com.botts.impl.system.lane.config.OcrConfig;
import com.botts.impl.system.lane.config.RapiscanRPMConfig;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.sensor.PositionConfig;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.eml.EmlRuntimeRegistry;
import org.sensorhub.impl.utils.rad.model.Adjudication;
import org.sensorhub.impl.utils.rad.model.Occupancy;
import org.sensorhub.impl.utils.rad.model.OccupancyExtended;
import org.slf4j.Logger;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the ephemeral OSCAR Hands-On Training Sandbox &amp; Guided Academy
 * when {@code OSCAR_SANDBOX_MODE=true}.
 *
 * <p>Outside sandbox mode ({@link TrainingSandboxEnvironment#isSandboxMode()} == {@code false}),
 * {@link #start()} is a zero-allocation no-op with zero threads and zero open sockets,
 * while {@link #getHostControlBridge()} remains available to administrators in the
 * {@code Help (?)} launcher.</p>
 */
public final class TrainingSandboxManager implements Closeable {

    public static final String PROP_WORK_DIR = "oscar.sandbox.workDir";
    public static final String TRACK_OPERATOR = "operator";
    public static final String TRACK_SUPERVISOR = "supervisor";
    public static final String TRACK_ADMINISTRATOR = "administrator";

    public record CompletedLabRecord(String trackId, String labId, String labTitle, String status) {}

    private final ISensorHub hub;
    private final OSCARServiceModule oscarModule;
    private final RuntimeExtensionsManager extensionsManager;
    private final Logger log;
    private final SandboxHostControlBridge hostControlBridge;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong commandSeq = new AtomicLong(900_000L);
    private final Object stageLock = new Object();

    private Path workDir;
    private Path uffShareDir;
    private SandboxRapiscanLoopbackServer rapiscan1;
    private SandboxRapiscanLoopbackServer rapiscan2;
    private SandboxRtspCameraStreamer cameraStreamer;
    private ExecutorService backgroundSeeder;
    private volatile String activeTrack = TRACK_OPERATOR;
    private volatile String stageStatusMessage = "Ready";
    private volatile boolean stagingInProgress = false;

    public TrainingSandboxManager(
            ISensorHub hub,
            OSCARServiceModule oscarModule,
            RuntimeExtensionsManager extensionsManager,
            Logger log
    ) {
        this.hub = hub;
        this.oscarModule = oscarModule;
        this.extensionsManager = extensionsManager;
        this.log = log;
        this.hostControlBridge = new SandboxHostControlBridge(log);
    }

    public SandboxHostControlBridge getHostControlBridge() {
        return hostControlBridge;
    }

    public synchronized void start() {
        if (!TrainingSandboxEnvironment.isSandboxMode()) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }

        if (System.getProperty("oscar.sandbox.extendedOccupancySeconds") == null) {
            System.setProperty("oscar.sandbox.extendedOccupancySeconds", "8");
        }

        try {
            this.workDir = resolveWorkDir();
            Files.createDirectories(this.workDir);

            // Install deterministic educational EML assessor unless an external archive is already loaded
            EmlRuntimeRegistry emlReg = EmlRuntimeRegistry.forHub(hub);
            if (!emlReg.isLoaded()) {
                emlReg.installInMemoryAlgorithm("oscar-sandbox-eml-assessor.jar", new SandboxEmlTrainingAlgorithm());
            }

            int reqPort1 = Integer.getInteger("oscar.sandbox.rapiscanPort1", TrainingSandboxEnvironment.DEFAULT_RAPISCAN_PORT_1);
            int reqPort2 = Integer.getInteger("oscar.sandbox.rapiscanPort2", TrainingSandboxEnvironment.DEFAULT_RAPISCAN_PORT_2);
            this.rapiscan1 = new SandboxRapiscanLoopbackServer(reqPort1, "DemoLane01", log);
            this.rapiscan1.start();
            this.rapiscan2 = new SandboxRapiscanLoopbackServer(reqPort2, "DemoLane02", log);
            this.rapiscan2.start();

            boolean skipRtsp = Boolean.getBoolean("oscar.sandbox.skipRtsp");
            if (!skipRtsp) {
                int rtspPort = Integer.getInteger("oscar.sandbox.rtspPort", TrainingSandboxEnvironment.DEFAULT_RTSP_PORT);
                this.cameraStreamer = new SandboxRtspCameraStreamer(workDir.resolve("camera"), rtspPort, log);
                this.cameraStreamer.start();
            }

            this.uffShareDir = SandboxSampleAssets.populateLocalUffSampleShare(workDir);

            this.backgroundSeeder = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "oscar-sandbox-track-stager");
                t.setDaemon(true);
                return t;
            });

            if (!Boolean.getBoolean("oscar.sandbox.skipAutoStage")) {
                String initialTrack = System.getenv("OSCAR_SANDBOX_INITIAL_TRACK");
                String targetTrack = (initialTrack != null && !initialTrack.isBlank())
                        ? initialTrack.trim().toLowerCase(Locale.ROOT)
                        : TRACK_OPERATOR;
                backgroundSeeder.submit(() -> {
                    try {
                        Thread.sleep(1_200L);
                        stageTrack(targetTrack);
                    } catch (Exception e) {
                        log.warn("Initial sandbox track staging encountered an issue: {}", e.getMessage());
                    }
                });
            }

            log.info("OSCAR Hands-On Training Sandbox active (RPM1=127.0.0.1:{}, RPM2=127.0.0.1:{}, UFF={})",
                    rapiscan1.getListeningPort(), rapiscan2.getListeningPort(), uffShareDir);
        } catch (Exception e) {
            log.error("Failed initializing TrainingSandboxManager", e);
        }
    }

    private static Path resolveWorkDir() {
        String override = System.getProperty(PROP_WORK_DIR);
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim()).toAbsolutePath().normalize();
        }
        return Paths.get("/var/lib/oscar/sandbox-tutorial");
    }

    public int getRapiscanPort1() {
        return rapiscan1 != null ? rapiscan1.getListeningPort() : TrainingSandboxEnvironment.DEFAULT_RAPISCAN_PORT_1;
    }

    public int getRapiscanPort2() {
        return rapiscan2 != null ? rapiscan2.getListeningPort() : TrainingSandboxEnvironment.DEFAULT_RAPISCAN_PORT_2;
    }

    public int getRtspPort() {
        return cameraStreamer != null ? cameraStreamer.getRtspPort() : TrainingSandboxEnvironment.DEFAULT_RTSP_PORT;
    }

    public Map<String, Object> getSandboxActiveStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean sandbox = TrainingSandboxEnvironment.isSandboxMode();
        out.put("sandboxMode", sandbox);
        if (!sandbox) {
            return out;
        }

        out.put("watermark", TrainingSandboxEnvironment.WATERMARK_TEXT);
        out.put("activeTrack", activeTrack);
        out.put("stagingInProgress", stagingInProgress);
        out.put("stageStatusMessage", stageStatusMessage);
        out.put("startedEpochSeconds", TrainingSandboxEnvironment.getStartedEpochSeconds());
        out.put("expiresEpochSeconds", TrainingSandboxEnvironment.getExpiresEpochSeconds());
        out.put("remainingSeconds", TrainingSandboxEnvironment.getRemainingSeconds());
        out.put("ttlMinutes", TrainingSandboxEnvironment.getTtlMinutes());
        out.put("archiveEnabled", false);

        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("rapiscanHost", "127.0.0.1");
        endpoints.put("rapiscanPort1", getRapiscanPort1());
        endpoints.put("rapiscanPort2", getRapiscanPort2());
        endpoints.put("cameraType", "Custom");
        endpoints.put("cameraHost", "127.0.0.1:" + getRtspPort());
        endpoints.put("cameraPath1", "/tutorial-cam");
        endpoints.put("cameraPath2", "/tutorial-cam-2");
        endpoints.put("rtspUrl1", "rtsp://127.0.0.1:" + getRtspPort() + "/tutorial-cam");
        endpoints.put("rtspLive", cameraStreamer != null && cameraStreamer.isRtspLive());
        endpoints.put("containerId1", TrainingSandboxEnvironment.CONTAINER_1);
        endpoints.put("containerId2", TrainingSandboxEnvironment.CONTAINER_2);
        endpoints.put("plate1", TrainingSandboxEnvironment.PLATE_1);
        endpoints.put("localUffSamplePath", uffShareDir != null ? uffShareDir.toString() : "/var/lib/oscar/sandbox-tutorial/uff-share");
        out.put("loopbackEndpoints", endpoints);

        out.put("samples", SandboxSampleAssets.listAvailableSamples());
        out.put("verification", buildVerificationSnapshot());
        return out;
    }

    public Map<String, Object> injectScenario(String scenario, Integer laneSlot) {
        requireSandboxActive();
        int slot = (laneSlot != null && laneSlot == 2) ? 2 : 1;
        SandboxRapiscanLoopbackServer target = (slot == 2) ? rapiscan2 : rapiscan1;
        if (target == null) {
            throw new IllegalStateException("Sandbox Rapiscan loopback emulator is not started.");
        }
        String normScenario = scenario == null ? "" : scenario.trim().toLowerCase(Locale.ROOT);
        boolean isFaultOnly = "fault_gamma_high".equals(normScenario)
                || "fault_tamper".equals(normScenario)
                || "clear_faults".equals(normScenario);
        if (!isFaultOnly && cameraStreamer != null && !target.isBusyOccupancy()) {
            cameraStreamer.triggerLaneTransit(slot, "extended_occupancy".equals(normScenario));
        }
        String message = target.injectScenarioAsync(scenario);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("scenario", scenario);
        res.put("laneSlot", slot);
        res.put("message", message);
        return res;
    }

    public Map<String, Object> stageTrack(String rawTrack) throws Exception {
        requireSandboxActive();
        String track = rawTrack == null ? TRACK_OPERATOR : rawTrack.trim().toLowerCase(Locale.ROOT);
        if (!Set.of(TRACK_OPERATOR, TRACK_SUPERVISOR, TRACK_ADMINISTRATOR).contains(track)) {
            throw new IllegalArgumentException("Unsupported training track: " + rawTrack);
        }

        synchronized (stageLock) {
            stagingInProgress = true;
            activeTrack = track;
            stageStatusMessage = "Staging " + track + " environment...";
            try {
                if (rapiscan1 != null) {
                    rapiscan1.injectScenarioAsync("clear_faults");
                }
                if (rapiscan2 != null) {
                    rapiscan2.injectScenarioAsync("clear_faults");
                }

                switch (track) {
                    case TRACK_ADMINISTRATOR -> {
                        removeAllLanes();
                        if (extensionsManager != null && extensionsManager.getCargoUffManager() != null) {
                            extensionsManager.getCargoUffManager().resetToDisabled();
                        }
                        stageStatusMessage = "Administrator blank-slate ready (0 lanes configured). Loopback Rapiscan 127.0.0.1:"
                                + getRapiscanPort1() + " and RTSP 127.0.0.1:" + getRtspPort() + "/tutorial-cam are online.";
                    }
                    case TRACK_OPERATOR -> {
                        removeAllLanes();
                        enableSandboxUffSampleShare();
                        LaneSystem lane1 = ensureSandboxLaneLoaded(
                                "DemoLane01",
                                "demo-lane-01",
                                List.of("north-gate"),
                                35.9312,
                                -84.3100,
                                getRapiscanPort1(),
                                "/tutorial-cam",
                                true);
                        waitForRpmClientConnected(rapiscan1, 6_000L);
                        // Trigger one initial Gamma Alarm asynchronously so the Operator Queue has a live event ready
                        if (rapiscan1 != null && !rapiscan1.isBusyOccupancy()) {
                            if (cameraStreamer != null) {
                                cameraStreamer.triggerLaneTransit(1, false);
                            }
                            rapiscan1.injectScenarioAsync("gamma_alarm");
                        }
                        stageStatusMessage = "Gatehouse Operator track staged: "
                                + (lane1 != null ? lane1.getName() : "DemoLane01")
                                + " online with live radiation, RTSP video, OCR, and Cargo NII samples.";
                    }
                    case TRACK_SUPERVISOR -> {
                        removeAllLanes();
                        enableSandboxUffSampleShare();
                        LaneSystem lane1 = ensureSandboxLaneLoaded(
                                "DemoLane01",
                                "demo-lane-01",
                                List.of("north-gate"),
                                35.9312,
                                -84.3100,
                                getRapiscanPort1(),
                                "/tutorial-cam",
                                true);
                        LaneSystem lane2 = ensureSandboxLaneLoaded(
                                "DemoLane02",
                                "demo-lane-02",
                                List.of("south-gate"),
                                35.9288,
                                -84.3075,
                                getRapiscanPort2(),
                                "/tutorial-cam-2",
                                true);
                        waitForRpmClientConnected(rapiscan1, 6_000L);
                        waitForRpmClientConnected(rapiscan2, 6_000L);
                        seedSupervisorMultiCategoryDataset(lane1, lane2);
                        stageStatusMessage = "Supervisor & Analyst track staged: 2 Operational View lanes (`north-gate`, `south-gate`) with multi-category SNM/Medical/NORM/EML history.";
                    }
                }
            } finally {
                stagingInProgress = false;
            }
        }
        return getSandboxActiveStatus();
    }

    private void enableSandboxUffSampleShare() {
        if (extensionsManager == null || extensionsManager.getCargoUffManager() == null || uffShareDir == null) {
            return;
        }
        try {
            extensionsManager.getCargoUffManager().updateConfig(
                    true,
                    "local",
                    "",
                    "",
                    uffShareDir.toString(),
                    "",
                    "",
                    null,
                    false,
                    120,
                    true);
        } catch (Exception e) {
            log.debug("Unable to pre-enable sandbox local UFF directory: {}", e.getMessage());
        }
    }

    private void removeAllLanes() {
        if (hub == null || hub.getModuleRegistry() == null) {
            return;
        }
        var reg = hub.getModuleRegistry();
        Collection<LaneSystem> existing = new ArrayList<>(reg.getLoadedModules(LaneSystem.class));
        for (LaneSystem lane : existing) {
            try {
                String id = lane.getLocalID();
                reg.destroyModule(id);
            } catch (Exception e) {
                log.debug("Failed removing sandbox lane {}: {}", lane.getName(), e.getMessage());
            }
        }
    }

    private LaneSystem ensureSandboxLaneLoaded(
            String name,
            String uniqueId,
            List<String> views,
            double lat,
            double lon,
            int rpmPort,
            String camPath,
            boolean enableOcr
    ) throws Exception {
        var reg = hub.getModuleRegistry();
        for (LaneSystem existing : reg.getLoadedModules(LaneSystem.class)) {
            if (uniqueId.equals(existing.getConfiguration().uniqueID)) {
                return existing;
            }
        }

        LaneConfig cfg = new LaneConfig();
        cfg.id = UUID.randomUUID().toString();
        cfg.moduleClass = LaneSystem.class.getCanonicalName();
        cfg.name = name;
        cfg.uniqueID = uniqueId;
        cfg.autoStart = true;
        cfg.autoDelete = true;
        cfg.operationalViewKeys = new ArrayList<>(views);

        PositionConfig.LLALocation loc = new PositionConfig.LLALocation();
        loc.lat = lat;
        loc.lon = lon;
        cfg.location = loc;

        RapiscanRPMConfig rpm = new RapiscanRPMConfig();
        rpm.remoteHost = "127.0.0.1";
        rpm.remotePort = rpmPort;
        rpm.emlConfig = new RapiscanRPMConfig.EMLConfig();
        rpm.emlConfig.emlEnabled = true;
        rpm.emlConfig.isCollimated = true;
        rpm.emlConfig.laneWidth = 4.8;

        cfg.laneOptionsConfig = new LaneOptionsConfig();
        cfg.laneOptionsConfig.rpmConfig = rpm;

        if (cameraStreamer != null && cameraStreamer.isRtspLive()) {
            CustomCameraConfig cam = new CustomCameraConfig();
            cam.remoteHost = "127.0.0.1:" + cameraStreamer.getRtspPort();
            cam.streamPath = camPath;
            cam.username = "";
            cam.password = "";
            cfg.laneOptionsConfig.ffmpegConfig = List.of(cam);
        }

        cfg.ocrConfig = new OcrConfig();
        cfg.ocrConfig.enabled = enableOcr && (cameraStreamer != null && cameraStreamer.isRtspLive());
        cfg.ocrConfig.alarmsOnly = false;
        cfg.ocrConfig.framesPerVideo = 4;

        var loaded = (LaneSystem) reg.loadModule(cfg);
        if (!loaded.isStarted()) {
            reg.startModule(cfg.id);
        }
        loaded.waitForState(ModuleEvent.ModuleState.STARTED, 10_000L);
        return loaded;
    }

    private static void waitForRpmClientConnected(SandboxRapiscanLoopbackServer server, long timeoutMs) {
        if (server == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (server.getConnectedClientCount() > 0) {
                return;
            }
            try {
                Thread.sleep(120L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void seedSupervisorMultiCategoryDataset(LaneSystem lane1, LaneSystem lane2) {
        if (lane1 == null || rapiscan1 == null) {
            return;
        }
        try {
            // Inject rapid deterministic passes on Lane 1 & Lane 2, then submit structured adjudications
            // so UpSet SNM / Medical / NORM charts and Multi-Pass Vehicle Incident search are populated!
            int beforeCount = countTotalOccupancies();
            if (cameraStreamer != null) {
                cameraStreamer.triggerLaneTransit(1, false);
                cameraStreamer.triggerLaneTransit(2, false);
            }
            rapiscan1.injectScenarioAsync("gamma_alarm");
            if (rapiscan2 != null) {
                rapiscan2.injectScenarioAsync("neutron_alarm");
            }
            waitForOccupancyCountAtLeast(beforeCount + 2, 10_000L);

            int secondBase = countTotalOccupancies();
            if (cameraStreamer != null) {
                cameraStreamer.triggerLaneTransit(1, false);
                cameraStreamer.triggerLaneTransit(2, false);
            }
            rapiscan1.injectScenarioAsync("norm_eml");
            if (rapiscan2 != null) {
                rapiscan2.injectScenarioAsync("gamma_neutron_alarm");
            }
            waitForOccupancyCountAtLeast(secondBase + 2, 10_000L);

            // Adjudicate the un-adjudicated alarms with SNM (U-235 + Cs-137), SNM (Pu), and Medical (Tc-99m)
            // plus multi-pass container ID SCFU8572724 across both gates!
            IObsSystemDatabase db = hub.getDatabaseRegistry().getFederatedDatabase();
            IdEncoder obsEncoder = hub.getIdEncoders().getObsIdEncoder();
            List<UnadjudicatedAlarmRef> pending = findUnadjudicatedAlarms(db, obsEncoder);

            int idx = 0;
            for (UnadjudicatedAlarmRef ref : pending) {
                LaneSystem owner = (lane2 != null && ref.systemUid.contains("tutorial-lane-02")) ? lane2 : lane1;
                if (idx == 0) {
                    submitSeededAdjudication(
                            owner,
                            ref.encodedObsId,
                            1,
                            List.of("U-235", "Cs-137"),
                            Adjudication.SecondaryInspectionStatus.COMPLETED,
                            TrainingSandboxEnvironment.CONTAINER_1,
                            "Training seed: Confirmed shielded SNM + Industrial co-occurrence at North Gate.");
                } else if (idx == 1) {
                    submitSeededAdjudication(
                            owner,
                            ref.encodedObsId,
                            1,
                            List.of("Pu"),
                            Adjudication.SecondaryInspectionStatus.COMPLETED,
                            TrainingSandboxEnvironment.CONTAINER_1,
                            "Training seed: Secondary gate confirmation pass for multi-pass vehicle correlation.");
                } else if (idx == 2) {
                    submitSeededAdjudication(
                            owner,
                            ref.encodedObsId,
                            3,
                            List.of("Tc"),
                            Adjudication.SecondaryInspectionStatus.COMPLETED,
                            TrainingSandboxEnvironment.PLATE_2,
                            "Training seed: Verified radiopharmaceutical outpatient passenger.");
                }
                idx++;
            }

            // Leave 1 fresh un-adjudicated alarm on Lane 1 so queue/adjudication demos remain active too
            if (cameraStreamer != null) {
                cameraStreamer.triggerLaneTransit(1, false);
            }
            rapiscan1.injectScenarioAsync("gamma_alarm");
        } catch (Exception e) {
            log.debug("Supervisor dataset seeding completed with warning: {}", e.getMessage());
        }
    }

    private record UnadjudicatedAlarmRef(String encodedObsId, String systemUid) {}

    private List<UnadjudicatedAlarmRef> findUnadjudicatedAlarms(IObsSystemDatabase db, IdEncoder obsEncoder) {
        List<UnadjudicatedAlarmRef> list = new ArrayList<>();
        if (db == null || db.getObservationStore() == null || obsEncoder == null) {
            return list;
        }
        var obsStore = db.getObservationStore();
        obsStore.selectEntries(new ObsFilter.Builder()
                .withDataStreams(new DataStreamFilter.Builder()
                        .withObservedProperties(RADHelper.getRadUri("Occupancy"))
                        .build())
                .build()).forEach(entry -> {
            IObsData obs = entry.getValue();
            IDataStreamInfo ds = obsStore.getDataStreams().get(new DataStreamKey(obs.getDataStreamID()));
            if (ds == null) {
                return;
            }
            Occupancy occ = OccupancyExtended.readOccupancy(ds.getRecordStructure(), obs.getResult());
            if ((occ.hasGammaAlarm() || occ.hasNeutronAlarm())
                    && (occ.getAdjudicatedIds() == null || occ.getAdjudicatedIds().isEmpty())) {
                list.add(new UnadjudicatedAlarmRef(
                        obsEncoder.encodeID(entry.getKey()),
                        ds.getSystemID() != null ? ds.getSystemID().getUniqueID() : ""));
            }
        });
        return list;
    }

    private void submitSeededAdjudication(
            LaneSystem lane,
            String encodedObsId,
            int code,
            List<String> isotopes,
            Adjudication.SecondaryInspectionStatus secStatus,
            String vehicleId,
            String feedback
    ) {
        if (lane == null || lane.getCommandInputs() == null) {
            return;
        }
        var ctrl = lane.getCommandInputs().get(AdjudicationControl.NAME);
        if (!(ctrl instanceof AdjudicationControl adjControl)) {
            return;
        }
        try {
            Adjudication adj = new Adjudication.Builder()
                    .adjudicationCode(code)
                    .isotopes(isotopes)
                    .secondaryInspectionStatus(secStatus)
                    .filePaths(List.of())
                    .occupancyObsId(encodedObsId)
                    .vehicleId(vehicleId)
                    .feedback(feedback)
                    .build();
            CommandData cmd = new CommandData.Builder()
                    .withId(org.sensorhub.api.common.BigId.fromLong(0, commandSeq.incrementAndGet()))
                    .withCommandStream(BigIdHelper.zeroBigId())
                    .withSender("sandbox-supervisor")
                    .withParams(Adjudication.fromAdjudication(adj))
                    .build();
            adjControl.submitCommand(cmd).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Seeded adjudication skipped: {}", e.getMessage());
        }
    }

    private static final class BigIdHelper {
        static org.sensorhub.api.common.BigId zeroBigId() {
            return org.sensorhub.api.common.BigId.fromLong(0, 1L);
        }
    }

    private int countTotalOccupancies() {
        try {
            IObsSystemDatabase db = hub.getDatabaseRegistry().getFederatedDatabase();
            if (db == null || db.getObservationStore() == null) {
                return 0;
            }
            return (int) db.getObservationStore().countMatchingEntries(new ObsFilter.Builder()
                    .withDataStreams(new DataStreamFilter.Builder()
                            .withObservedProperties(RADHelper.getRadUri("Occupancy"))
                            .build())
                    .build());
        } catch (Exception e) {
            return 0;
        }
    }

    private void waitForOccupancyCountAtLeast(int target, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countTotalOccupancies() >= target) {
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Computes live, outcome-based verification metrics directly from OSCAR's real
     * module registry, federated observation store, command status store, bucket store,
     * and runtime extensions manager so the Training Coach auto-verifies real actions.
     */
    public Map<String, Object> buildVerificationSnapshot() {
        Map<String, Object> v = new LinkedHashMap<>();
        int configuredLanes = 0;
        int startedLanes = 0;
        int lanesWithCamera = 0;
        int lanesWithOcr = 0;
        Set<String> viewKeys = new HashSet<>();

        if (hub != null && hub.getModuleRegistry() != null) {
            for (LaneSystem lane : hub.getModuleRegistry().getLoadedModules(LaneSystem.class)) {
                configuredLanes++;
                if (lane.isStarted()) {
                    startedLanes++;
                }
                LaneConfig lcfg = lane.getConfiguration();
                if (lcfg != null) {
                    if (lcfg.laneOptionsConfig != null
                            && lcfg.laneOptionsConfig.ffmpegConfig != null
                            && !lcfg.laneOptionsConfig.ffmpegConfig.isEmpty()) {
                        lanesWithCamera++;
                    }
                    if (lcfg.ocrConfig != null && lcfg.ocrConfig.enabled) {
                        lanesWithOcr++;
                    }
                    if (lcfg.operationalViewKeys != null) {
                        viewKeys.addAll(lcfg.operationalViewKeys);
                    }
                }
            }
        }

        long totalOccupancies = 0;
        long gammaAlarms = 0;
        long neutronAlarms = 0;
        long code9999Count = 0;
        long code9998Count = 0;
        long operatorAdjudicatedCount = 0;
        long adjudicatedWithIsotopeOrFileCount = 0;
        long adjudicatedWithVehicleIdCount = 0;
        Map<String, Integer> vehiclePassCounts = new HashMap<>();

        try {
            IObsSystemDatabase db = hub != null && hub.getDatabaseRegistry() != null
                    ? hub.getDatabaseRegistry().getFederatedDatabase()
                    : null;
            if (db != null && db.getObservationStore() != null) {
                var obsStore = db.getObservationStore();
                obsStore.select(new ObsFilter.Builder()
                        .withDataStreams(new DataStreamFilter.Builder()
                                .withObservedProperties(RADHelper.getRadUri("Occupancy"))
                                .build())
                        .build()).forEach(obs -> {
                    IDataStreamInfo ds = obsStore.getDataStreams().get(new DataStreamKey(obs.getDataStreamID()));
                    if (ds == null) {
                        return;
                    }
                    Occupancy occ = OccupancyExtended.readOccupancy(ds.getRecordStructure(), obs.getResult());
                    if (occ == null) {
                        return;
                    }
                });
                // Iterate cleanly with mutable counters
                var list = obsStore.select(new ObsFilter.Builder()
                        .withDataStreams(new DataStreamFilter.Builder()
                                .withObservedProperties(RADHelper.getRadUri("Occupancy"))
                                .build())
                        .build()).toList();
                for (IObsData obs : list) {
                    IDataStreamInfo ds = obsStore.getDataStreams().get(new DataStreamKey(obs.getDataStreamID()));
                    if (ds == null) {
                        continue;
                    }
                    Occupancy occ = OccupancyExtended.readOccupancy(ds.getRecordStructure(), obs.getResult());
                    if (occ == null) {
                        continue;
                    }
                    totalOccupancies++;
                    if (occ.hasGammaAlarm()) {
                        gammaAlarms++;
                    }
                    if (occ.hasNeutronAlarm()) {
                        neutronAlarms++;
                    }
                    if (occ.getAdjudicatedIds() != null) {
                        boolean has9999 = occ.getAdjudicatedIds().contains(Adjudication.EML_NON_THREAT_MARKER)
                                || occ.getAdjudicatedIds().contains("code:9999");
                        boolean has9998 = occ.getAdjudicatedIds().contains(Adjudication.EXTENDED_OCCUPANCY_GAMMA_MARKER)
                                || occ.getAdjudicatedIds().contains("code:9998");
                        if (has9999) {
                            code9999Count++;
                        }
                        if (has9998) {
                            code9998Count++;
                        }
                        boolean humanAdj = occ.getAdjudicatedIds().stream()
                                .anyMatch(id -> id != null && !id.isBlank() && !Adjudication.isSyntheticMarker(id));
                        if (humanAdj) {
                            operatorAdjudicatedCount++;
                        }
                    }
                }
            }

            if (db != null && db.getCommandStatusStore() != null) {
                var statuses = db.getCommandStatusStore().select(new CommandStatusFilter.Builder()
                        .withStatus(ICommandStatus.CommandStatusCode.COMPLETED)
                        .build()).toList();
                for (ICommandStatus st : statuses) {
                    if (st.getResult() == null || st.getResult().getInlineRecords() == null) {
                        continue;
                    }
                    for (var rec : st.getResult().getInlineRecords()) {
                        if (rec == null || rec.getAtomCount() < 6) {
                            continue;
                        }
                        try {
                            String sender = rec.getStringValue(0);
                            if ("EML".equalsIgnoreCase(sender) || "AUTO".equalsIgnoreCase(sender)) {
                                continue;
                            }
                            int isoCount = rec.getIntValue(3);
                            int secIdx = 4 + Math.max(0, isoCount);
                            int fileCount = rec.getIntValue(secIdx + 1);
                            int vehIdx = secIdx + 2 + Math.max(0, fileCount) + 1;
                            if (isoCount > 0 || fileCount > 0) {
                                adjudicatedWithIsotopeOrFileCount++;
                            }
                            if (vehIdx < rec.getAtomCount()) {
                                String rawVeh = rec.getStringValue(vehIdx);
                                String normVeh = VehicleIncidentAggregator.normalizeVehicleId(rawVeh);
                                if (!normVeh.isEmpty()) {
                                    adjudicatedWithVehicleIdCount++;
                                    vehiclePassCounts.merge(normVeh, 1, Integer::sum);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Verification snapshot query partial: {}", e.getMessage());
        }

        int multiPassVehicles = (int) vehiclePassCounts.values().stream().filter(c -> c >= 2).count();

        int generatedReports = 0;
        int uploadedAdjFiles = 0;
        try {
            if (oscarModule != null && oscarModule.getBucketService() != null
                    && oscarModule.getBucketService().getBucketStore() != null) {
                var store = oscarModule.getBucketService().getBucketStore();
                if (store.bucketExists(Constants.REPORT_BUCKET)) {
                    generatedReports = store.listObjects(Constants.REPORT_BUCKET).size();
                }
                if (store.bucketExists(Constants.ADJUDICATION_BUCKET)) {
                    uploadedAdjFiles = (int) store.listObjects(Constants.ADJUDICATION_BUCKET).stream()
                            .filter(k -> k != null && (k.endsWith(".n42") || k.endsWith(".uff") || k.endsWith(".uffref.json")))
                            .count();
                }
            }
        } catch (Exception ignored) {
        }

        boolean offlineMapActive = false;
        boolean policyPackActive = false;
        boolean uffEnabled = false;
        int uffIndexedCount = 0;
        if (extensionsManager != null) {
            try {
                Map<String, Object> mapInfo = extensionsManager.getOfflineMapManager().getPublicActiveMapInfo("/sensorhub/oscar/extensions");
                offlineMapActive = Boolean.TRUE.equals(mapInfo.get("available"));
            } catch (Exception ignored) {
            }
            try {
                Map<String, Object> polInfo = extensionsManager.getPolicyPackManager().getStatus(List.of());
                policyPackActive = Boolean.TRUE.equals(polInfo.get("customActive"));
            } catch (Exception ignored) {
            }
            try {
                Map<String, Object> uffInfo = extensionsManager.getCargoUffManager().getPublicActiveInfo();
                uffEnabled = Boolean.TRUE.equals(uffInfo.get("enabled"));
                Object ic = uffInfo.get("indexedCount");
                if (ic instanceof Number n) {
                    uffIndexedCount = n.intValue();
                }
            } catch (Exception ignored) {
            }
        }

        boolean activeFaultOrTamper = (rapiscan1 != null && rapiscan1.hasActiveFaultOrTamper())
                || (rapiscan2 != null && rapiscan2.hasActiveFaultOrTamper());

        v.put("configuredLaneCount", configuredLanes);
        v.put("onlineLaneCount", startedLanes);
        v.put("lanesWithCameraCount", lanesWithCamera);
        v.put("lanesWithOcrCount", lanesWithOcr);
        v.put("operationalViewCount", viewKeys.size());
        v.put("totalOccupancies", totalOccupancies);
        v.put("gammaAlarmCount", gammaAlarms);
        v.put("neutronAlarmCount", neutronAlarms);
        v.put("emlCode9999Count", code9999Count);
        v.put("extendedCode9998Count", code9998Count);
        v.put("activeFaultOrTamper", activeFaultOrTamper);
        v.put("operatorAdjudicatedCount", operatorAdjudicatedCount);
        v.put("adjudicatedWithEvidenceCount", adjudicatedWithIsotopeOrFileCount + uploadedAdjFiles);
        v.put("adjudicatedWithVehicleIdCount", adjudicatedWithVehicleIdCount);
        v.put("multiPassVehicleCount", multiPassVehicles);
        v.put("generatedReportCount", generatedReports);
        v.put("offlineMapActive", offlineMapActive);
        v.put("policyPackActive", policyPackActive);
        v.put("cargoUffEnabled", uffEnabled);
        v.put("cargoUffIndexedCount", uffIndexedCount);
        return v;
    }

    /**
     * Generates a crisp 1-page vector PDF Training Completion Checklist using
     * Apache PDFBox 3 + embedded Noto Sans so trainees or supervisors can save an
     * offline training record before the ephemeral sandbox is flushed.
     */
    public byte[] renderCompletionCertificatePdf(
            String traineeName,
            List<CompletedLabRecord> completedLabs
    ) throws IOException {
        String safeTrainee = (traineeName == null || traineeName.isBlank())
                ? "OSCAR Trainee"
                : traineeName.trim();
        List<CompletedLabRecord> rows = completedLabs == null ? List.of() : completedLabs;
        String nodeId = (oscarModule != null && oscarModule.getConfiguration() != null
                && oscarModule.getConfiguration().nodeId != null)
                ? oscarModule.getConfiguration().nodeId
                : "oscar-sandbox";

        ByteArrayOutputStream out = new ByteArrayOutputStream(32_768);
        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder(
                "OSCAR Hands-On Training Completion Record",
                nodeId,
                PdfDocumentBuilder.Orientation.PORTRAIT)) {

            Instant now = Instant.now();
            pdf.drawReportBanner(new PdfDocumentBuilder.HeaderMetadata(
                    "OSCAR 3.11.7 Hands-On Training Completion Record",
                    nodeId,
                    "Trainee: " + safeTrainee,
                    TrainingSandboxEnvironment.WATERMARK_TEXT + "  |  Zero Production Database / Parquet Impact",
                    Instant.ofEpochSecond(TrainingSandboxEnvironment.getStartedEpochSeconds()),
                    now
            ));

            Map<String, Object> snap = buildVerificationSnapshot();
            long verifiedLabs = rows.stream()
                    .filter(r -> r != null && "verified".equalsIgnoreCase(r.status()))
                    .count();

            pdf.drawKpiCards(List.of(
                    new PdfDocumentBuilder.KpiCard(
                            "Verified Labs",
                            verifiedLabs + " / " + Math.max(rows.size(), 1),
                            "Hands-On Academy Modules",
                            PdfDocumentBuilder.ACCENT_GREEN),
                    new PdfDocumentBuilder.KpiCard(
                            "Practice Occupancies",
                            String.valueOf(snap.getOrDefault("totalOccupancies", 0)),
                            "Loopback Rapiscan + RTSP",
                            PdfDocumentBuilder.ACCENT_SKY),
                    new PdfDocumentBuilder.KpiCard(
                            "Adjudications",
                            String.valueOf(snap.getOrDefault("operatorAdjudicatedCount", 0)),
                            "Code 9999: " + snap.getOrDefault("emlCode9999Count", 0)
                                    + " | 9998: " + snap.getOrDefault("extendedCode9998Count", 0),
                            PdfDocumentBuilder.ACCENT_BLUE),
                    new PdfDocumentBuilder.KpiCard(
                            "Practice Reports",
                            String.valueOf(snap.getOrDefault("generatedReportCount", 0)),
                            "Ephemeral Sandbox Storage",
                            new Color(217, 119, 6))
            ));

            pdf.drawSectionHeader(
                    "Completed Training Modules & Outcome Verification",
                    "Automatically verified against live OpenSensorHub ConSys, REST, and Runtime Extension APIs",
                    72f);

            List<List<String>> tableRows = new ArrayList<>();
            if (rows.isEmpty()) {
                tableRows.add(List.of(
                        activeTrack.toUpperCase(Locale.ROOT),
                        "SANDBOX-SESSION",
                        "Interactive Sandbox Exploration",
                        "COMPLETED"));
            } else {
                for (CompletedLabRecord r : rows) {
                    if (r == null) {
                        continue;
                    }
                    tableRows.add(List.of(
                            r.trackId() != null ? r.trackId() : "-",
                            r.labId() != null ? r.labId() : "-",
                            r.labTitle() != null ? r.labTitle() : "-",
                            r.status() != null ? r.status().toUpperCase(Locale.ROOT) : "COMPLETED"));
                }
            }

            new TableGenerator().drawTable(
                    pdf,
                    List.of(
                            new TableGenerator.ColumnSpec("Track", 0.18f, TableGenerator.Align.LEFT, true),
                            new TableGenerator.ColumnSpec("Lab ID", 0.16f, TableGenerator.Align.LEFT),
                            new TableGenerator.ColumnSpec("Training Module", 0.48f, TableGenerator.Align.LEFT),
                            new TableGenerator.ColumnSpec("Status", 0.18f, TableGenerator.Align.CENTER, true)),
                    tableRows,
                    false,
                    8.6f);

            pdf.finishAndSave(out);
        }
        return out.toByteArray();
    }

    private static void requireSandboxActive() {
        if (!TrainingSandboxEnvironment.isSandboxMode()) {
            throw new IllegalStateException("This endpoint is only active inside the ephemeral Training Sandbox (OSCAR_SANDBOX_MODE=true).");
        }
    }

    @Override
    public synchronized void close() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (backgroundSeeder != null) {
            backgroundSeeder.shutdownNow();
            backgroundSeeder = null;
        }
        if (cameraStreamer != null) {
            cameraStreamer.close();
            cameraStreamer = null;
        }
        if (rapiscan1 != null) {
            rapiscan1.close();
            rapiscan1 = null;
        }
        if (rapiscan2 != null) {
            rapiscan2.close();
            rapiscan2 = null;
        }
    }
}
