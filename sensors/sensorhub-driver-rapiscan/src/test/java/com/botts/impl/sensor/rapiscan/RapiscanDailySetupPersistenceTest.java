package com.botts.impl.sensor.rapiscan;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.SensorHubConfig;
import org.sensorhub.impl.module.ModuleConfigJsonFile;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.sensor.SensorSystem;
import org.sensorhub.impl.sensor.SensorSystemConfig;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RapiscanDailySetupPersistenceTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private File configFile;
    private SensorHub hub;
    private ModuleRegistry registry;

    @Before
    public void setUp() throws Exception {
        configFile = tempFolder.newFile("config.json");
        Files.writeString(configFile.toPath(), "[]", StandardCharsets.UTF_8);

        SensorHubConfig hubConfig = new SensorHubConfig(
                configFile.getAbsolutePath(), tempFolder.getRoot().getAbsolutePath());
        ModuleConfigJsonFile configRepo = new ModuleConfigJsonFile(configFile.getAbsolutePath(), false);
        hub = new SensorHub(hubConfig, configRepo);
        hub.start();
        registry = hub.getModuleRegistry();
        RapiscanConfigSaver.setDebounceMillis(50L);
        RapiscanConfigSaver.resetCommitCount(registry);
    }

    @After
    public void tearDown() {
        RapiscanConfigSaver.setDebounceMillis(RapiscanConfigSaver.DEFAULT_DEBOUNCE_MILLIS);
        if (hub != null) {
            hub.stop();
        }
    }

    @Test
    public void dailyStreamSetupParametersOverwriteJsonConfigAndPreserveFractionalNSigma() throws Exception {
        SensorSystemConfig laneConfig = new SensorSystemConfig();
        laneConfig.id = UUID.randomUUID().toString();
        laneConfig.name = "Lane 1";
        laneConfig.uniqueID = "lane1";
        laneConfig.moduleClass = SensorSystem.class.getCanonicalName();
        laneConfig.autoStart = false;

        RapiscanConfig rpmConfig = new RapiscanConfig();
        rpmConfig.id = UUID.randomUUID().toString();
        rpmConfig.name = "Lane 1 - RPM";
        rpmConfig.serialNumber = "lane1";
        rpmConfig.laneID = 1;
        rpmConfig.moduleClass = RapiscanSensor.class.getCanonicalName();
        rpmConfig.autoStart = false;

        SensorSystemConfig.SystemMember member = new SensorSystemConfig.SystemMember();
        member.config = rpmConfig;
        laneConfig.subsystems.add(member);

        SensorSystem laneSystem = (SensorSystem) registry.loadModule(laneConfig);
        registry.saveConfiguration(laneConfig);

        RapiscanSensor sensor = (RapiscanSensor) laneSystem.getMembers().get(rpmConfig.id);
        assertNotNull("Expected RapiscanSensor child in SensorSystem", sensor);
        sensor.createOutputs();
        sensor.getSetupGammaOutput().registerListener(sensor.getGammaThresholdOutput());

        MessageHandler handler = new MessageHandler(new ByteArrayInputStream(new byte[0]), sensor);
        Instant now = Instant.parse("2026-11-09T00:32:23.258Z");
        try {
            handler.onNewMainChar("SG1", new String[]{
                    "SG1", "000525", "000070", "06", "12", "08.3", "P", "00-32-23.236", "03-32-23.236"
            }, now);
            handler.onNewMainChar("SG2", new String[]{
                    "SG2", "1110", "0.072", "0.460", "02", "1010", "T", "00-32-23.258", "03-32-23.258"
            }, now);
            handler.onNewMainChar("SG3", new String[]{
                    "SG3", "0.071", "0.459", "030", "002", "1.12.0", "00-32-23.258", "03-32-23.258"
            }, now);
            handler.onNewMainChar("SN1", new String[]{
                    "SN1", "000065", "04", "0005", "1350", "02", "180", "00-32-23.264", "03-32-23.264"
            }, now);
            handler.onNewMainChar("SN2", new String[]{
                    "SN2", "0.512", "5.120", "0.515", "5.150", "PP", "00-32-23.302", "03-32-23.302"
            }, now);

            // Verify fractional nsigma propagated to GammaThresholdOutput without integer truncation
            assertEquals(8.3, sensor.getGammaThresholdOutput().getNSigma(), 1e-6);

            // Flush debounced saver and verify a single coalesced commit occurred for both SG and SN
            RapiscanConfigSaver.flushNow(registry);
            assertEquals(1, RapiscanConfigSaver.getCommitCount(registry));

            // Re-read config.json from disk into a fresh repository instance to verify persistence
            ModuleConfigJsonFile reloadedRepo = new ModuleConfigJsonFile(configFile.getAbsolutePath(), false);
            SensorSystemConfig persistedLane = (SensorSystemConfig) reloadedRepo.get(laneConfig.id);
            assertNotNull(persistedLane);
            assertEquals(1, persistedLane.subsystems.size());

            RapiscanConfig persistedRpm = (RapiscanConfig) persistedLane.subsystems.get(0).config;
            assertNotNull(persistedRpm);
            assertNotNull(persistedRpm.setupGammaConfig);
            assertNotNull(persistedRpm.setupNeutronConfig);

            assertEquals(525, persistedRpm.setupGammaConfig.highBackgroundFault);
            assertEquals(70, persistedRpm.setupGammaConfig.lowBackgroundFault);
            assertEquals(6, persistedRpm.setupGammaConfig.intervals);
            assertEquals(12, persistedRpm.setupGammaConfig.occupancyHoldin);
            assertEquals(8.3, persistedRpm.setupGammaConfig.nsigma, 1e-6);
            assertEquals("1110", persistedRpm.setupGammaConfig.detectors);
            assertEquals(0.072, persistedRpm.setupGammaConfig.controlLowLevelDiscriminator, 1e-6);
            assertEquals(0.460, persistedRpm.setupGammaConfig.controlUpperLevelDiscriminator, 1e-6);
            assertEquals(2, persistedRpm.setupGammaConfig.relayOutput);
            assertEquals("1010", persistedRpm.setupGammaConfig.algorithm);
            assertEquals("T", persistedRpm.setupGammaConfig.softwareVersion);
            assertEquals(0.071, persistedRpm.setupGammaConfig.slaveLowLevelDiscriminator, 1e-6);
            assertEquals(0.459, persistedRpm.setupGammaConfig.slaveUpperLevelDiscriminator, 1e-6);
            assertEquals(30, persistedRpm.setupGammaConfig.backgroundTime);
            assertEquals(2.0, persistedRpm.setupGammaConfig.backgroundSigma, 1e-6);
            assertEquals("1.12.0", persistedRpm.setupGammaConfig.firmwareVersion);

            assertEquals(65, persistedRpm.setupNeutronConfig.highBackgroundFault);
            assertEquals(4, persistedRpm.setupNeutronConfig.maxIntervals);
            assertEquals(5.0, persistedRpm.setupNeutronConfig.alphaValue, 1e-6);
            assertEquals(1350.0, persistedRpm.setupNeutronConfig.zMaxValue, 1e-6);
            assertEquals(2, persistedRpm.setupNeutronConfig.sequentialIntervals);
            assertEquals(180, persistedRpm.setupNeutronConfig.backgroundTime);
            assertEquals(0.512, persistedRpm.setupNeutronConfig.controlLowLevelDiscriminator, 1e-6);
            assertEquals(5.120, persistedRpm.setupNeutronConfig.controlUpperLevelDiscriminator, 1e-6);
            assertEquals(0.515, persistedRpm.setupNeutronConfig.slaveLowLevelDiscriminator, 1e-6);
            assertEquals(5.150, persistedRpm.setupNeutronConfig.slaveUpperLevelDiscriminator, 1e-6);

            // Re-sending identical daily parameters on the next day must be a no-op (zero disk writes)
            handler.onNewMainChar("SG1", new String[]{
                    "SG1", "000525", "000070", "06", "12", "08.3", "P", "00-32-23.236", "03-32-23.236"
            }, now.plusSeconds(86400));
            handler.onNewMainChar("SG2", new String[]{
                    "SG2", "1110", "0.072", "0.460", "02", "1010", "T", "00-32-23.258", "03-32-23.258"
            }, now.plusSeconds(86400));
            handler.onNewMainChar("SG3", new String[]{
                    "SG3", "0.071", "0.459", "030", "002", "1.12.0", "00-32-23.258", "03-32-23.258"
            }, now.plusSeconds(86400));
            handler.onNewMainChar("SN1", new String[]{
                    "SN1", "000065", "04", "0005", "1350", "02", "180", "00-32-23.264", "03-32-23.264"
            }, now.plusSeconds(86400));
            handler.onNewMainChar("SN2", new String[]{
                    "SN2", "0.512", "5.120", "0.515", "5.150", "PP", "00-32-23.302", "03-32-23.302"
            }, now.plusSeconds(86400));

            RapiscanConfigSaver.flushNow(registry);
            assertEquals("Identical daily setup lines must not trigger redundant config.json writes",
                    1, RapiscanConfigSaver.getCommitCount(registry));
        } finally {
            handler.stop();
        }
    }

    @Test
    public void fiftyLaneMidnightSetupBurstCoalescesIntoSingleNonBlockingCommit() throws Exception {
        RapiscanConfigSaver.setDebounceMillis(500L);
        List<RapiscanSensor> sensors = new ArrayList<>(50);
        List<MessageHandler> handlers = new ArrayList<>(50);
        List<ModuleConfig> initialConfigs = new ArrayList<>(50);

        for (int i = 1; i <= 50; i++) {
            SensorSystemConfig laneConfig = new SensorSystemConfig();
            laneConfig.id = "lane-id-" + i;
            laneConfig.name = "Lane " + i;
            laneConfig.uniqueID = "lane" + i;
            laneConfig.moduleClass = SensorSystem.class.getCanonicalName();
            laneConfig.autoStart = false;

            RapiscanConfig rpmConfig = new RapiscanConfig();
            rpmConfig.id = "rpm-id-" + i;
            rpmConfig.name = "Lane " + i + " - RPM";
            rpmConfig.serialNumber = "rpm" + i;
            rpmConfig.laneID = i;
            rpmConfig.moduleClass = RapiscanSensor.class.getCanonicalName();
            rpmConfig.autoStart = false;

            SensorSystemConfig.SystemMember member = new SensorSystemConfig.SystemMember();
            member.config = rpmConfig;
            laneConfig.subsystems.add(member);

            SensorSystem laneSystem = (SensorSystem) registry.loadModule(laneConfig);
            initialConfigs.add(laneConfig);

            RapiscanSensor sensor = (RapiscanSensor) laneSystem.getMembers().get(rpmConfig.id);
            sensor.createOutputs();
            sensors.add(sensor);
            handlers.add(new MessageHandler(new ByteArrayInputStream(new byte[0]), sensor));
        }
        registry.saveConfiguration(initialConfigs.toArray(new ModuleConfig[0]));
        RapiscanConfigSaver.resetCommitCount(registry);

        Instant midnight = Instant.parse("2026-11-10T00:00:00.000Z");
        long startNanos = System.nanoTime();
        try {
            for (int i = 0; i < 50; i++) {
                int laneNum = i + 1;
                MessageHandler h = handlers.get(i);
                h.onNewMainChar("SG1", new String[]{
                        "SG1", String.valueOf(500 + laneNum), "000070", "05", "10", "08.5", "P", "00-00-00.000", "00-00-00.000"
                }, midnight);
                h.onNewMainChar("SG2", new String[]{
                        "SG2", "1111", "0.069", "0.455", "01", "1010", "A", "00-00-00.000", "00-00-00.000"
                }, midnight);
                h.onNewMainChar("SG3", new String[]{
                        "SG3", "0.069", "0.455", "020", "000", "1.10.1", "00-00-00.000", "00-00-00.000"
                }, midnight);
                h.onNewMainChar("SN1", new String[]{
                        "SN1", String.valueOf(50 + laneNum), "03", "0004", "1200", "01", "120", "00-00-00.000", "00-00-00.000"
                }, midnight);
                h.onNewMainChar("SN2", new String[]{
                        "SN2", "0.504", "5.040", "0.504", "5.040", "PP", "00-00-00.000", "00-00-00.000"
                }, midnight);
            }
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            assertTrue("Reader thread burst processing should not block on disk I/O (took " + elapsedMillis + " ms)",
                    elapsedMillis < 250L);

            RapiscanConfigSaver.flushNow(registry);
            assertEquals("All 50 lanes'SG+SN updates should coalesce into 1 repository commit",
                    1, RapiscanConfigSaver.getCommitCount(registry));

            ModuleConfigJsonFile reloadedRepo = new ModuleConfigJsonFile(configFile.getAbsolutePath(), false);
            for (int i = 1; i <= 50; i++) {
                SensorSystemConfig persistedLane = (SensorSystemConfig) reloadedRepo.get("lane-id-" + i);
                RapiscanConfig persistedRpm = (RapiscanConfig) persistedLane.subsystems.get(0).config;
                assertEquals(500 + i, persistedRpm.setupGammaConfig.highBackgroundFault);
                assertEquals(8.5, persistedRpm.setupGammaConfig.nsigma, 1e-6);
                assertEquals(50 + i, persistedRpm.setupNeutronConfig.highBackgroundFault);
            }
        } finally {
            for (MessageHandler h : handlers) {
                h.stop();
            }
        }
    }
}
