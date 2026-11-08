package com.botts.impl.system.lane.helpers.occupancy;

import com.botts.impl.process.rs350.occupancy.DailyFileStruct;
import com.botts.impl.process.rs350.occupancy.Rs350OccupancyProcessModule;
import com.botts.impl.sensor.aspect.AspectSensor;
import com.botts.impl.sensor.rapiscan.RapiscanSensor;
import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.LaneConfig;
import net.opengis.swe.v20.DataComponent;
import org.junit.Test;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.eml.PendingExtendedOccupancyAutoAdjudicationRegistry;
import org.sensorhub.impl.utils.rad.model.Adjudication;
import org.sensorhub.impl.utils.rad.model.Occupancy;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.*;

public class OccupancyStatusOutputTest {

    private static final Instant CLEAR_TIME = Instant.parse("2026-09-24T12:00:00Z");
    private static final Instant OCCUPIED_TIME = Instant.parse("2026-09-24T12:00:10.250Z");
    private static final Instant RS350_SOURCE_START = Instant.parse("2026-09-24T12:00:09.100Z");
    private static final Instant ALARM_TIME = Instant.parse("2026-09-24T12:00:20Z");
    private static final Instant END_TIME = Instant.parse("2026-09-24T12:00:30Z");

    @Test
    public void rapiscanPublishesCanonicalOccupancyLifecycle() {
        assertCanonicalLifecycle(
                new RapiscanSensor(),
                () -> textDailyFile("GB,0"),
                () -> textDailyFile("GS,1"),
                () -> textDailyFile("GA,1"),
                () -> textDailyFile("GB,0"));
    }

    @Test
    public void aspectPublishesCanonicalOccupancyLifecycle() {
        assertCanonicalLifecycle(
                new AspectSensor(),
                () -> textDailyFile("0,0,0,0,0,0,0,0,0,0"),
                () -> textDailyFile("0,1,0,0,0,0,0,0,0,0"),
                () -> textDailyFile("0,1,1,0,0,0,0,0,0,0"),
                () -> textDailyFile("0,0,0,0,0,0,0,0,0,0"));
    }

    @Test
    public void rs350PublishesCanonicalOccupancyLifecycle() {
        var output = newTestOutput();
        var wrapper = new OccupancyWrapper(null, new Rs350OccupancyProcessModule(), output);

        assertNull("Unknown state must not be represented as clear", output.getLatestRecord());

        assertTrue(wrapper.processDailyFile(rs350DailyFile(false, false, null), CLEAR_TIME));
        assertStatus(output, false, 0.0, CLEAR_TIME);

        // A foreground report without an alarm is still a real RS350 occupancy.
        assertTrue(wrapper.processDailyFile(
                rs350DailyFile(true, false, RS350_SOURCE_START), OCCUPIED_TIME));
        assertStatus(output, true, epochSeconds(RS350_SOURCE_START), OCCUPIED_TIME);

        assertTrue(wrapper.processDailyFile(
                rs350DailyFile(true, true, RS350_SOURCE_START), ALARM_TIME));
        assertStatus(output, true, epochSeconds(RS350_SOURCE_START), OCCUPIED_TIME);

        assertTrue(wrapper.processDailyFile(rs350DailyFile(false, false, null), END_TIME));
        assertStatus(output, false, 0.0, END_TIME);
    }

    @Test
    public void malformedSampleDoesNotTurnUnknownIntoClear() {
        var output = newTestOutput();
        var wrapper = new OccupancyWrapper(null, new RapiscanSensor(), output);

        assertFalse(wrapper.processDailyFile(textDailyFile("unrecognized"), CLEAR_TIME));
        assertNull(output.getLatestRecord());
    }

    @Test
    public void outputAdvertisesStableDiscoveryContract() {
        var output = newTestOutput();
        var record = output.getRecordDescription();

        assertEquals(OccupancyStatusOutput.NAME, output.getName());
        assertEquals(OccupancyStatusOutput.OCCUPANCY_STATUS_DEFINITION,
                record.getComponent("isOccupied").getDefinition());
        assertEquals(OccupancyStatusOutput.OCCUPANCY_START_TIME_DEFINITION,
                record.getComponent("occupancyStartTime").getDefinition());
        assertEquals(OccupancyStatusOutput.EXTENDED_OCCUPANCY_THRESHOLD_MINUTES_DEFINITION,
                record.getComponent("extendedOccupancyThresholdMinutes").getDefinition());
        assertEquals(OccupancyStatusOutput.EXTENDED_OCCUPANCY_FAULT_DEFINITION,
                record.getComponent("extendedOccupancyFault").getDefinition());
        assertEquals(OccupancyStatusOutput.EXTENDED_OCCUPANCY_ALARM_STATE_DEFINITION,
                record.getComponent("alarmState").getDefinition());
        assertEquals(1, new LaneConfig().extendedOccupancyThresholdMinutes);
        assertEquals(1, output.getExtendedOccupancyThresholdMinutes());
    }

    @Test
    public void logsExtendedOccupancyFaultAtConfiguredThresholdAndClearsOnExit() {
        var output = newTestOutput();
        var wrapper = new OccupancyWrapper(null, new RapiscanSensor(), output);
        assertEquals(1, wrapper.getExtendedOccupancyThresholdMinutes());

        assertTrue(wrapper.processDailyFile(textDailyFile("GB,0"), CLEAR_TIME));
        assertStatus(output, false, 0.0, CLEAR_TIME, false, OccupancyStatusOutput.CLEAR_ALARM_STATE);

        assertTrue(wrapper.processDailyFile(textDailyFile("GS,1"), OCCUPIED_TIME));
        assertStatus(output, true, epochSeconds(OCCUPIED_TIME), OCCUPIED_TIME, false, OccupancyStatusOutput.CLEAR_ALARM_STATE);

        // Before 1-minute threshold: no extended occupancy fault
        Instant beforeThreshold = OCCUPIED_TIME.plusSeconds(59);
        assertTrue(wrapper.processDailyFile(textDailyFile("GS,1"), beforeThreshold));
        assertStatus(output, true, epochSeconds(OCCUPIED_TIME), OCCUPIED_TIME, false, OccupancyStatusOutput.CLEAR_ALARM_STATE);

        // At 1-minute threshold: publishes Fault - Extended Occupancy once
        Instant atThreshold = OCCUPIED_TIME.plusSeconds(60);
        assertTrue(wrapper.processDailyFile(textDailyFile("GS,1"), atThreshold));
        assertStatus(output, true, epochSeconds(OCCUPIED_TIME), atThreshold, true, OccupancyStatusOutput.FAULT_EXTENDED_OCCUPANCY_STATE);

        // Subsequent sample during same extended occupancy does not duplicate the fault publication
        Instant afterThreshold = OCCUPIED_TIME.plusSeconds(75);
        assertTrue(wrapper.processDailyFile(textDailyFile("GS,1"), afterThreshold));
        assertStatus(output, true, epochSeconds(OCCUPIED_TIME), atThreshold, true, OccupancyStatusOutput.FAULT_EXTENDED_OCCUPANCY_STATE);

        // Leaving occupancy clears the fault state
        Instant clearedTime = OCCUPIED_TIME.plusSeconds(80);
        assertTrue(wrapper.processDailyFile(textDailyFile("GB,0"), clearedTime));
        assertStatus(output, false, 0.0, clearedTime, false, OccupancyStatusOutput.CLEAR_ALARM_STATE);
    }

    @Test
    public void autoAdjudicatesGammaOnlyAlarmDuringExtendedOccupancyButNotNeutronAlarms() {
        var output = newTestOutput();
        var rapiscan = new RapiscanSensor() {
            @Override
            public String getUniqueIdentifier() {
                return "urn:osh:sensor:rapiscan:lane-ext-test";
            }
        };
        var wrapper = new OccupancyWrapper(null, rapiscan, output);
        wrapper.setExtendedOccupancyThresholdMinutes(2);

        double startSec = 1_760_000_000.0;
        // 1. Gamma-only alarm during a 150s extended occupancy (threshold = 2 min = 120s) -> auto-adjudicated with Code 9998
        Occupancy extendedGammaOnly = new Occupancy.Builder()
                .samplingTime(startSec + 150.0)
                .occupancyCount(101)
                .startTime(startSec)
                .endTime(startSec + 150.0)
                .gammaAlarm(true)
                .neutronAlarm(false)
                .maxGammaCount(2450)
                .maxNeutronCount(3)
                .adjudicatedIds(List.of())
                .build();
        wrapper.evaluateCompletedOccupancy(extendedGammaOnly);

        assertTrue(extendedGammaOnly.getAdjudicatedIds().contains(Adjudication.EXTENDED_OCCUPANCY_GAMMA_MARKER));
        assertEquals(Adjudication.EXTENDED_OCCUPANCY_GAMMA_CODE,
                extendedGammaOnly.getLatestAdjudicationCode().orElse(-1));
        var claimed = PendingExtendedOccupancyAutoAdjudicationRegistry.claim(
                rapiscan.getUniqueIdentifier(),
                extendedGammaOnly.getSamplingTime(),
                extendedGammaOnly.getOccupancyCount());
        assertNotNull("Expected pending auto-adjudication notes to be registered", claimed);
        assertTrue(claimed.feedback().contains("Gamma-only alarm (no neutron alarm) occurred during an extended occupancy"));
        assertTrue(claimed.feedback().contains("lane threshold 2 min"));

        // 2. Gamma + Neutron alarm during an extended occupancy -> MUST NOT be auto-adjudicated
        Occupancy extendedWithNeutron = new Occupancy.Builder()
                .samplingTime(startSec + 320.0)
                .occupancyCount(102)
                .startTime(startSec + 160.0)
                .endTime(startSec + 320.0)
                .gammaAlarm(true)
                .neutronAlarm(true)
                .maxGammaCount(2600)
                .maxNeutronCount(85)
                .adjudicatedIds(List.of())
                .build();
        wrapper.evaluateCompletedOccupancy(extendedWithNeutron);
        assertTrue("Alarms including neutrons must never be auto-adjudicated",
                extendedWithNeutron.getAdjudicatedIds().isEmpty());
        assertNull(PendingExtendedOccupancyAutoAdjudicationRegistry.claim(
                rapiscan.getUniqueIdentifier(),
                extendedWithNeutron.getSamplingTime(),
                extendedWithNeutron.getOccupancyCount()));

        // 3. Gamma-only alarm during a normal (non-extended, 15s < 120s) occupancy -> MUST NOT be auto-adjudicated as 9998
        wrapper.processDailyFile(textDailyFile("GB,0"), Instant.ofEpochSecond((long) (startSec + 400)));
        Occupancy normalGammaOnly = new Occupancy.Builder()
                .samplingTime(startSec + 420.0)
                .occupancyCount(103)
                .startTime(startSec + 405.0)
                .endTime(startSec + 420.0)
                .gammaAlarm(true)
                .neutronAlarm(false)
                .maxGammaCount(1900)
                .maxNeutronCount(2)
                .adjudicatedIds(List.of())
                .build();
        wrapper.evaluateCompletedOccupancy(normalGammaOnly);
        assertTrue("Non-extended occupancy alarms must not be auto-adjudicated as Extended Occupancy Gamma Alarm",
                normalGammaOnly.getAdjudicatedIds().isEmpty());
    }

    private static void assertCanonicalLifecycle(
            IDataProducerModule<?> rpm,
            Supplier<DataComponent> clearSample,
            Supplier<DataComponent> occupiedSample,
            Supplier<DataComponent> alarmSample,
            Supplier<DataComponent> endSample) {
        var output = newTestOutput();
        var wrapper = new OccupancyWrapper(null, rpm, output);

        assertNull("Unknown state must not be represented as clear", output.getLatestRecord());

        assertTrue(wrapper.processDailyFile(clearSample.get(), CLEAR_TIME));
        assertStatus(output, false, 0.0, CLEAR_TIME);

        assertTrue(wrapper.processDailyFile(occupiedSample.get(), OCCUPIED_TIME));
        assertStatus(output, true, epochSeconds(OCCUPIED_TIME), OCCUPIED_TIME);

        assertTrue(wrapper.processDailyFile(alarmSample.get(), ALARM_TIME));
        assertStatus(output, true, epochSeconds(OCCUPIED_TIME), OCCUPIED_TIME);

        assertTrue(wrapper.processDailyFile(endSample.get(), END_TIME));
        assertStatus(output, false, 0.0, END_TIME);
    }

    private static void assertStatus(
            OccupancyStatusOutput output,
            boolean occupied,
            double expectedStart,
            Instant expectedPublishTime) {
        assertStatus(output, occupied, expectedStart, expectedPublishTime, false, OccupancyStatusOutput.CLEAR_ALARM_STATE);
    }

    private static void assertStatus(
            OccupancyStatusOutput output,
            boolean occupied,
            double expectedStart,
            Instant expectedPublishTime,
            boolean expectedExtendedFault,
            String expectedAlarmState) {
        var record = output.getLatestRecord();
        assertNotNull(record);
        assertEquals(occupied, record.getBooleanValue(1));
        assertEquals(expectedStart, record.getDoubleValue(2), 0.000_001);
        assertEquals(output.getExtendedOccupancyThresholdMinutes(), record.getIntValue(3));
        assertEquals(expectedExtendedFault, record.getBooleanValue(4));
        assertEquals(expectedAlarmState, record.getStringValue(5));
        assertEquals(expectedPublishTime.toEpochMilli(), output.getLatestRecordTime());
    }

    private static DataComponent textDailyFile(String message) {
        var radHelper = new RADHelper();
        var record = radHelper.createRecord()
                .name("dailyFile")
                .addField("samplingTime", radHelper.createPrecisionTimeStamp())
                .addField("message", radHelper.createText().name("message").build())
                .build();
        record.renewDataBlock();
        record.getData().setDoubleValue(0, epochSeconds(CLEAR_TIME));
        record.getData().setStringValue(1, message);
        return record;
    }

    private static DataComponent rs350DailyFile(boolean occupied, boolean alarming, Instant occupancyStart) {
        var record = DailyFileStruct.getRecordDescription();
        record.renewDataBlock();
        record.getComponent("samplingTime").getData().setDoubleValue(epochSeconds(CLEAR_TIME));
        record.getComponent(DailyFileStruct.OCCUPIED_NAME).getData().setBooleanValue(occupied);
        record.getComponent(DailyFileStruct.OCCUPANCY_START_TIME_NAME).getData()
                .setDoubleValue(occupancyStart == null ? 0.0 : epochSeconds(occupancyStart));
        record.getComponent(DailyFileStruct.ALARM_NAME).getData().setBooleanValue(alarming);
        return record;
    }

    private static double epochSeconds(Instant instant) {
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    private static OccupancyStatusOutput newTestOutput() {
        return new OccupancyStatusOutput(new LaneSystem() {
            @Override
            public String getUniqueIdentifier() {
                return "urn:osh:system:lane:occupancy-status-test";
            }
        });
    }
}
