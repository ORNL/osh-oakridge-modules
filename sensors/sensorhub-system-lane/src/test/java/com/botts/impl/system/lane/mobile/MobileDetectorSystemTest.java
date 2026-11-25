package com.botts.impl.system.lane.mobile;

import com.botts.impl.sensor.kromek.d5.D5Sensor;
import com.botts.impl.system.lane.config.CustomCameraConfig;
import com.botts.impl.system.lane.config.D5RPMConfig;
import com.botts.impl.system.lane.config.LaneOptionsConfig;
import com.botts.impl.system.lane.config.OcrConfig;
import com.botts.impl.system.lane.config.RapiscanRPMConfig;
import com.botts.impl.system.lane.config.WebRelayConfig;
import com.botts.impl.system.lane.mobile.config.MobileDetectorConfig;
import org.junit.After;
import org.junit.Test;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.impl.SensorHub;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MobileDetectorSystemTest {

    private SensorHub hub;

    @After
    public void tearDown() {
        if (hub != null) {
            hub.stop();
            hub = null;
        }
    }

    @Test
    public void enforcesMobileConstraintsPreservesLaneUidAndAdvertisesMobileSensorMlMetadata() throws Exception {
        hub = new SensorHub();
        hub.start();
        var reg = hub.getModuleRegistry();

        // 1. Rejects non-mobile RPM types such as Rapiscan
        MobileDetectorConfig invalidCfg = (MobileDetectorConfig) reg.createModuleConfig(new MobileDetectorDescriptor());
        invalidCfg.id = UUID.randomUUID().toString();
        invalidCfg.name = "Bad Mobile";
        invalidCfg.uniqueID = "bad-mobile";
        invalidCfg.autoStart = false;
        RapiscanRPMConfig rapiscan = new RapiscanRPMConfig();
        rapiscan.remoteHost = "198.51.100.99";
        rapiscan.remotePort = 10001;
        invalidCfg.detectorConfig = rapiscan;

        MobileDetectorSystem invalidModule = (MobileDetectorSystem) reg.loadModule(invalidCfg);
        try {
            invalidModule.init();
        } catch (SensorHubException expectedSync) {
            // Synchronous init path
        }
        long errDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (invalidModule.getCurrentError() == null && System.nanoTime() < errDeadline) {
            Thread.sleep(20L);
        }
        assertNotNull("Expected MobileDetectorSystem to reject Rapiscan detector config",
                invalidModule.getCurrentError());

        // 2. Valid Kromek D5 mobile walker clears portal options, sets location output, preserves urn:osh:system:lane:* UID,
        // and sets MobileDetectorLane definition + oscar:mobile-detector:true keyword
        MobileDetectorConfig mobileCfg = (MobileDetectorConfig) reg.createModuleConfig(new MobileDetectorDescriptor());
        mobileCfg.id = UUID.randomUUID().toString();
        mobileCfg.name = "Patrol D5";
        mobileCfg.uniqueID = "patrol-d5";
        mobileCfg.autoStart = false;
        mobileCfg.operationalViewKeys = new ArrayList<>(List.of("roving-team"));

        // Populate portal-only options that MobileDetectorSystem must strip on init
        mobileCfg.laneOptionsConfig = new LaneOptionsConfig();
        CustomCameraConfig strayCam = new CustomCameraConfig();
        strayCam.remoteHost = "198.51.100.70:8554";
        strayCam.streamPath = "/cam";
        mobileCfg.laneOptionsConfig.ffmpegConfig = new ArrayList<>(List.of(strayCam));
        mobileCfg.ocrConfig = new OcrConfig();
        mobileCfg.ocrConfig.enabled = true;
        mobileCfg.webRelayConfig = new WebRelayConfig();
        mobileCfg.webRelayConfig.enabled = true;

        D5RPMConfig d5 = new D5RPMConfig();
        d5.remoteHost = "198.51.100.60";
        d5.remotePort = 10111;
        mobileCfg.detectorConfig = d5;

        MobileDetectorSystem mobile = (MobileDetectorSystem) reg.loadModule(mobileCfg);
        mobile.init();
        assertTrue(mobile.waitForState(ModuleEvent.ModuleState.INITIALIZED, 5_000));

        assertEquals("urn:osh:system:lane:patrol-d5", mobile.getUniqueIdentifier());
        assertFalse(mobile.getConfiguration().ocrConfig.enabled);
        assertFalse(mobile.getConfiguration().webRelayConfig.enabled);
        assertNull(mobile.getOutputs().get("vehicleOcr"));
        assertNull(mobile.getCommandInputs().get("laneRelay"));

        D5Sensor d5Child = mobile.getMembers().values().stream()
                .filter(D5Sensor.class::isInstance)
                .map(D5Sensor.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull("Expected D5Sensor child module", d5Child);
        assertTrue(d5Child.getConfiguration().outputs.enableLocationOutput);
        assertNotNull("Expected initialized location output on mobile D5 child",
                d5Child.getOutputs().get("location"));

        var sml = mobile.getCurrentDescription();
        assertNotNull(sml);
        assertEquals(MobileDetectorSystem.MOBILE_DETECTOR_SYSTEM_DEFINITION, sml.getDefinition());

        List<String> keywords = new ArrayList<>();
        sml.getKeywordsList().forEach(kwList -> keywords.addAll(kwList.getKeywordList()));
        assertTrue(keywords.contains(MobileDetectorSystem.MOBILE_DETECTOR_KEYWORD));
        assertTrue(keywords.contains("oscar:view:roving-team"));
        assertFalse("Mobile detector must not advertise portal extended-occupancy threshold keyword",
                keywords.stream().anyMatch(k -> k.startsWith("oscar:extended-occupancy-threshold-min:")));
        assertFalse("Mobile detector must not advertise portal webrelay keyword",
                keywords.contains("oscar:webrelay-enabled:true"));
    }
}
