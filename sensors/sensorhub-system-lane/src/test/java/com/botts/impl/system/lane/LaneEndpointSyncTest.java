package com.botts.impl.system.lane;

import com.botts.impl.sensor.aspect.AspectConfig;
import com.botts.impl.sensor.rapiscan.RapiscanConfig;
import com.botts.impl.sensor.rs350.RS350Config;
import com.botts.impl.system.lane.config.AspectRPMConfig;
import com.botts.impl.system.lane.config.AxisCameraConfig;
import com.botts.impl.system.lane.config.ConnectionConfig;
import com.botts.impl.system.lane.config.CustomCameraConfig;
import com.botts.impl.system.lane.config.LaneConfig;
import com.botts.impl.system.lane.config.LaneOptionsConfig;
import com.botts.impl.system.lane.config.RS350RPMConfig;
import com.botts.impl.system.lane.config.RapiscanRPMConfig;
import org.junit.Test;
import org.sensorhub.impl.module.ModuleConfigJsonFile;
import org.sensorhub.impl.sensor.SensorSystemConfig;
import org.sensorhub.impl.sensor.ffmpeg.config.FFMPEGConfig;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

public class LaneEndpointSyncTest {

    @Test
    public void lastSyncedEndpointIsNonPublicForAdminFormsYetPersistedInJsonConfig() throws Exception {
        boolean anyPublicShadow = Arrays.stream(ConnectionConfig.class.getFields())
                .anyMatch(f -> f.getName().contains("Synced"));
        assertFalse("Shadow sync field must not be public (kept hidden from Vaadin GenericConfigForm)", anyPublicShadow);

        Path tempJson = Files.createTempFile("lane-endpoint-sync-", ".json");
        try {
            Files.writeString(tempJson, "[]", StandardCharsets.UTF_8);
            ModuleConfigJsonFile repo = new ModuleConfigJsonFile(tempJson.toString(), false);

            LaneConfig lane = new LaneConfig();
            lane.id = "lane-05-id";
            lane.uniqueID = "lane-05";
            lane.name = "Lane05";
            lane.moduleClass = LaneSystem.class.getCanonicalName();
            lane.laneOptionsConfig = new LaneOptionsConfig();
            lane.laneOptionsConfig.ffmpegConfig = new ArrayList<>();

            CustomCameraConfig cam = new CustomCameraConfig();
            cam.remoteHost = "10.0.0.10:8554";
            cam.streamPath = "/cam_05";
            lane.laneOptionsConfig.ffmpegConfig.add(cam);

            var initResult = LaneEndpointSync.reconcileCamera(lane, 0, cam, null);
            assertEquals(LaneEndpointSync.SyncAction.REGISTER_NEW_CHILD, initResult.action());
            FFMPEGConfig childCam = initResult.childConfig();
            childCam.id = "cam-05-id";

            SensorSystemConfig.SystemMember member = new SensorSystemConfig.SystemMember();
            member.config = childCam;
            lane.subsystems.add(member);

            repo.add(lane);
            repo.commit();

            // Simulate user editing ONLY connectionString in config.json on disk
            String rawJson = Files.readString(tempJson, StandardCharsets.UTF_8);
            assertTrue(rawJson.contains("lastSyncedEndpoint"));
            String editedChildOnly = rawJson.replace(
                    "\"connectionString\": \"rtsp://10.0.0.10:8554/cam_05\"",
                    "\"connectionString\": \"rtsp://10.0.0.99:8554/cam_05\"");
            Files.writeString(tempJson, editedChildOnly, StandardCharsets.UTF_8);

            ModuleConfigJsonFile reloadedRepo = new ModuleConfigJsonFile(tempJson.toString(), false);
            LaneConfig reloadedLane = (LaneConfig) reloadedRepo.get("lane-05-id");
            FFMPEGConfig reloadedChild = (FFMPEGConfig) reloadedLane.subsystems.get(0).config;

            var childEditResult = LaneEndpointSync.reconcileCamera(
                    reloadedLane,
                    0,
                    reloadedLane.laneOptionsConfig.ffmpegConfig.get(0),
                    reloadedChild);
            assertEquals(LaneEndpointSync.SyncAction.UPDATE_PARENT, childEditResult.action());
            assertEquals("10.0.0.99:8554", childEditResult.simpleConfig().remoteHost);
            assertEquals("/cam_05", ((CustomCameraConfig) childEditResult.simpleConfig()).streamPath);

            reloadedLane.laneOptionsConfig.ffmpegConfig.set(0, childEditResult.simpleConfig());
            reloadedRepo.update(reloadedLane);
            reloadedRepo.commit();

            // Now simulate user editing ONLY remoteHost in LaneOptionsConfig in config.json on disk
            String rawJson2 = Files.readString(tempJson, StandardCharsets.UTF_8);
            String editedParentOnly = rawJson2.replace(
                    "\"remoteHost\": \"10.0.0.99:8554\"",
                    "\"remoteHost\": \"10.0.0.200:8554\"");
            Files.writeString(tempJson, editedParentOnly, StandardCharsets.UTF_8);

            ModuleConfigJsonFile reloadedRepo2 = new ModuleConfigJsonFile(tempJson.toString(), false);
            LaneConfig reloadedLane2 = (LaneConfig) reloadedRepo2.get("lane-05-id");
            FFMPEGConfig reloadedChild2 = (FFMPEGConfig) reloadedLane2.subsystems.get(0).config;

            var parentEditResult = LaneEndpointSync.reconcileCamera(
                    reloadedLane2,
                    0,
                    reloadedLane2.laneOptionsConfig.ffmpegConfig.get(0),
                    reloadedChild2);
            assertEquals(LaneEndpointSync.SyncAction.UPDATE_CHILD, parentEditResult.action());
            assertEquals("rtsp://10.0.0.200:8554/cam_05", parentEditResult.childConfig().connection.connectionString);
        } finally {
            Files.deleteIfExists(tempJson);
        }
    }

    @Test
    public void sanitizesFullRtspUrlPastedIntoRemoteHostAndPreservesCredentialsWithSpecialChars() {
        LaneConfig lane = new LaneConfig();
        lane.uniqueID = "lane-01";
        lane.name = "Lane01";

        CustomCameraConfig cam = new CustomCameraConfig();
        cam.remoteHost = "rtsp://admin:p@ss:w0rd@10.20.30.40:554/h264Preview_01_main";
        cam.streamPath = "";

        var res = LaneEndpointSync.reconcileCamera(lane, 0, cam, null);
        assertEquals("10.20.30.40:554", cam.remoteHost);
        assertEquals("admin", cam.username);
        assertEquals("p@ss:w0rd", cam.password);
        assertEquals("/h264Preview_01_main", cam.streamPath);
        assertEquals("rtsp://admin:p@ss:w0rd@10.20.30.40:554/h264Preview_01_main",
                res.childConfig().connection.connectionString);

        // Editing child credentials + IP + custom path updates AxisCameraConfig cleanly
        AxisCameraConfig axis = new AxisCameraConfig();
        axis.remoteHost = "10.20.30.50";
        axis.username = "root";
        axis.password = "old";
        var axisInit = LaneEndpointSync.reconcileCamera(lane, 1, axis, null);
        FFMPEGConfig axisChild = axisInit.childConfig();
        axisChild.connection.connectionString = "rtsp://oper:newpass@10.20.30.60:8554/custom_stream";

        var axisReconciled = LaneEndpointSync.reconcileCamera(lane, 1, axis, axisChild);
        assertEquals(LaneEndpointSync.SyncAction.UPDATE_PARENT, axisReconciled.action());
        assertTrue(axisReconciled.simpleConfig() instanceof CustomCameraConfig);
        assertEquals("10.20.30.60:8554", axisReconciled.simpleConfig().remoteHost);
        assertEquals("oper", axisReconciled.simpleConfig().username);
        assertEquals("newpass", axisReconciled.simpleConfig().password);
        assertEquals("/custom_stream", ((CustomCameraConfig) axisReconciled.simpleConfig()).streamPath);
    }

    @Test
    public void reconcilesRpmHostAndPortBidirectionallyAcrossRapiscanAspectAndRs350() {
        LaneConfig lane = new LaneConfig();
        lane.uniqueID = "lane-10";
        lane.name = "Lane10";

        RapiscanRPMConfig rapiscan = new RapiscanRPMConfig();
        rapiscan.remoteHost = "10.1.1.10";
        rapiscan.remotePort = 10010;
        rapiscan.emlConfig.emlEnabled = true;

        var init = LaneEndpointSync.reconcileRpm(lane, rapiscan, null);
        assertEquals(LaneEndpointSync.SyncAction.REGISTER_NEW_CHILD, init.action());
        RapiscanConfig child = (RapiscanConfig) init.childConfig();
        assertEquals("10.1.1.10", child.commSettings.protocol.remoteHost);
        assertEquals(10010, child.commSettings.protocol.remotePort);

        // Parent edit -> updates child RPM
        rapiscan.remoteHost = "10.1.1.25:10025";
        var parentEdit = LaneEndpointSync.reconcileRpm(lane, rapiscan, child);
        assertEquals(LaneEndpointSync.SyncAction.UPDATE_CHILD, parentEdit.action());
        assertEquals("10.1.1.25", rapiscan.remoteHost);
        assertEquals(10025, rapiscan.remotePort);
        assertEquals("10.1.1.25", child.commSettings.protocol.remoteHost);
        assertEquals(10025, child.commSettings.protocol.remotePort);

        // Child edit -> updates parent RPM
        child.commSettings.protocol.remoteHost = "10.1.1.77";
        child.commSettings.protocol.remotePort = 1601;
        var childEdit = LaneEndpointSync.reconcileRpm(lane, rapiscan, child);
        assertEquals(LaneEndpointSync.SyncAction.UPDATE_PARENT, childEdit.action());
        assertEquals("10.1.1.77", childEdit.simpleConfig().remoteHost);
        assertEquals(1601, childEdit.simpleConfig().remotePort);

        // Aspect & RS350 round-trips
        AspectRPMConfig aspect = new AspectRPMConfig();
        aspect.remoteHost = "10.2.2.10";
        aspect.remotePort = 502;
        AspectConfig aspectChild = (AspectConfig) LaneEndpointSync.reconcileRpm(lane, aspect, null).childConfig();
        aspectChild.commSettings.protocol.remoteHost = "10.2.2.20";
        var aspectSync = LaneEndpointSync.reconcileRpm(lane, aspect, aspectChild);
        assertEquals(LaneEndpointSync.SyncAction.UPDATE_PARENT, aspectSync.action());
        assertEquals("10.2.2.20", aspectSync.simpleConfig().remoteHost);

        RS350RPMConfig rs350 = new RS350RPMConfig();
        rs350.remoteHost = "10.3.3.10";
        rs350.remotePort = 5120;
        RS350Config rs350Child = (RS350Config) LaneEndpointSync.reconcileRpm(lane, rs350, null).childConfig();
        rs350.remoteHost = "10.3.3.30";
        var rs350Sync = LaneEndpointSync.reconcileRpm(lane, rs350, rs350Child);
        assertEquals(LaneEndpointSync.SyncAction.UPDATE_CHILD, rs350Sync.action());
        assertEquals("10.3.3.30", rs350Child.commSettings.protocol.remoteHost);
        assertTrue(Modifier.isPublic(LaneEndpointSync.class.getModifiers()));
    }

    @Test
    public void normalizesLegacyV35PortPrefixedCameraStreamPathsAcrossIpv4HostnameAndIpv6() {
        LaneConfig lane = new LaneConfig();
        lane.uniqueID = "lane-legacy-cam";
        lane.name = "LegacyCam";

        // 1. IPv4 host without port + ":9001/cam1" streamPath -> "198.51.100.10:9001" + "/cam1"
        CustomCameraConfig ipv4Cam = new CustomCameraConfig();
        ipv4Cam.remoteHost = "198.51.100.10";
        ipv4Cam.streamPath = ":9001/cam1";
        ipv4Cam.username = "oper";
        ipv4Cam.password = "secret";

        var ipv4Result = LaneEndpointSync.reconcileCamera(lane, 0, ipv4Cam, null);
        assertEquals("198.51.100.10:9001", ipv4Cam.remoteHost);
        assertEquals("/cam1", ipv4Cam.streamPath);
        assertEquals("rtsp://oper:secret@198.51.100.10:9001/cam1",
                ipv4Result.childConfig().connection.connectionString);

        // 2. Hostname without port + ":8554/h264Preview_01_main" -> "camera.example.test:8554" + "/h264Preview_01_main"
        CustomCameraConfig hostCam = new CustomCameraConfig();
        hostCam.remoteHost = "camera.example.test";
        hostCam.streamPath = ":8554/h264Preview_01_main";
        var hostResult = LaneEndpointSync.reconcileCamera(lane, 1, hostCam, null);
        assertEquals("camera.example.test:8554", hostCam.remoteHost);
        assertEquals("/h264Preview_01_main", hostCam.streamPath);
        assertEquals("rtsp://camera.example.test:8554/h264Preview_01_main",
                hostResult.childConfig().connection.connectionString);

        // 3. Bracketed IPv6 without port + ":9002/live/0" -> "[2001:db8::10]:9002" + "/live/0"
        CustomCameraConfig ipv6Cam = new CustomCameraConfig();
        ipv6Cam.remoteHost = "[2001:db8::10]";
        ipv6Cam.streamPath = ":9002/live/0";
        var ipv6Result = LaneEndpointSync.reconcileCamera(lane, 2, ipv6Cam, null);
        assertEquals("[2001:db8::10]:9002", ipv6Cam.remoteHost);
        assertEquals("/live/0", ipv6Cam.streamPath);
        assertEquals("rtsp://[2001:db8::10]:9002/live/0",
                ipv6Result.childConfig().connection.connectionString);

        // 4. Host that ALREADY carries an explicit port is never double-ported
        CustomCameraConfig explicitPortCam = new CustomCameraConfig();
        explicitPortCam.remoteHost = "198.51.100.20:554";
        explicitPortCam.streamPath = ":9001/cam1";
        LaneEndpointSync.sanitizeSimpleCameraConfig(explicitPortCam);
        assertEquals("198.51.100.20:554", explicitPortCam.remoteHost);
        assertEquals(":9001/cam1", explicitPortCam.streamPath);

        // 5. Upgraded config with already-persisted broken child URL "rtsp://198.51.100.10/:9001/cam1" heals child too
        CustomCameraConfig upgradedParent = new CustomCameraConfig();
        upgradedParent.remoteHost = "198.51.100.10";
        upgradedParent.streamPath = ":9001/cam1";
        upgradedParent.markSyncedEndpoint("rtsp://198.51.100.10/:9001/cam1|0");

        FFMPEGConfig brokenChild = new FFMPEGConfig();
        brokenChild.connection.connectionString = "rtsp://198.51.100.10/:9001/cam1";
        var healed = LaneEndpointSync.reconcileCamera(lane, 3, upgradedParent, brokenChild);
        assertEquals(LaneEndpointSync.SyncAction.UPDATE_CHILD, healed.action());
        assertEquals("198.51.100.10:9001", upgradedParent.remoteHost);
        assertEquals("/cam1", upgradedParent.streamPath);
        assertEquals("rtsp://198.51.100.10:9001/cam1", brokenChild.connection.connectionString);
    }
}
