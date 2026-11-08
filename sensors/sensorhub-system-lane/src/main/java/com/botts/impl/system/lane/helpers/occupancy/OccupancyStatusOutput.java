package com.botts.impl.system.lane.helpers.occupancy;

import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.LaneConfig;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.VarRateSensorOutput;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.vast.data.TextEncodingImpl;

import java.time.Instant;

/**
 * Lane-level, RPM-independent view of the current occupancy state and extended
 * occupancy fault state.
 *
 * <p>The output intentionally belongs to the lane rather than to a specific RPM
 * driver.  Consumers can therefore discover one stable stream and use the same
 * result contract for Rapiscan, Aspect, and RS350 lanes.</p>
 */
public class OccupancyStatusOutput extends VarRateSensorOutput<LaneSystem> {

    public static final String NAME = "occupancyStatus";
    public static final String OCCUPANCY_STATUS_DEFINITION = RADHelper.DEF_OCCUPANCY_STATUS;
    public static final String OCCUPANCY_START_TIME_DEFINITION = RADHelper.DEF_OCCUPANCY_START_TIME;
    public static final String EXTENDED_OCCUPANCY_THRESHOLD_MINUTES_DEFINITION = RADHelper.DEF_EXTENDED_OCCUPANCY_THRESHOLD_MINUTES;
    public static final String EXTENDED_OCCUPANCY_FAULT_DEFINITION = RADHelper.DEF_EXTENDED_OCCUPANCY_FAULT;
    public static final String EXTENDED_OCCUPANCY_ALARM_STATE_DEFINITION = RADHelper.DEF_EXTENDED_OCCUPANCY_ALARM_STATE;
    public static final String CLEAR_ALARM_STATE = "Clear";
    public static final String FAULT_EXTENDED_OCCUPANCY_STATE = RADHelper.FAULT_EXTENDED_OCCUPANCY;

    private final DataRecord recordStruct;
    private final DataEncoding recordEncoding;
    private volatile int extendedOccupancyThresholdMinutes = LaneConfig.DEFAULT_EXTENDED_OCCUPANCY_THRESHOLD_MINUTES;

    public OccupancyStatusOutput(LaneSystem parentSensor) {
        super(NAME, parentSensor, 0);

        if (parentSensor != null && parentSensor.getConfiguration() != null
                && parentSensor.getConfiguration().extendedOccupancyThresholdMinutes > 0) {
            this.extendedOccupancyThresholdMinutes = parentSensor.getConfiguration().extendedOccupancyThresholdMinutes;
        }

        var radHelper = new RADHelper();
        recordStruct = radHelper.createRecord()
                .name(NAME)
                .label("Occupancy Status")
                .description("Current lane occupancy state, active occupancy start time, configured extended occupancy threshold, and extended occupancy fault state")
                .updatable(true)
                .addField("samplingTime", radHelper.createPrecisionTimeStamp())
                .addField("isOccupied", radHelper.createBoolean()
                        .name("isOccupied")
                        .label("Is Occupied")
                        .definition(OCCUPANCY_STATUS_DEFINITION)
                        .description("True while an occupancy or alarming occupancy is active"))
                .addField("occupancyStartTime", radHelper.createTime()
                        .asPhenomenonTimeIsoUTC()
                        .name("occupancyStartTime")
                        .label("Occupancy Start Time")
                        .definition(OCCUPANCY_START_TIME_DEFINITION)
                        .description("Start of the active occupancy; zero when the lane is clear"))
                .addField("extendedOccupancyThresholdMinutes", radHelper.createCount()
                        .name("extendedOccupancyThresholdMinutes")
                        .label("Extended Occupancy Threshold (min)")
                        .definition(EXTENDED_OCCUPANCY_THRESHOLD_MINUTES_DEFINITION)
                        .description("Configured threshold in minutes before an active occupancy is logged as an extended occupancy fault"))
                .addField("extendedOccupancyFault", radHelper.createBoolean()
                        .name("extendedOccupancyFault")
                        .label("Extended Occupancy Fault")
                        .definition(EXTENDED_OCCUPANCY_FAULT_DEFINITION)
                        .description("True when an active occupancy exceeds the lane's configured extended occupancy threshold"))
                .addField("alarmState", radHelper.createCategory()
                        .name("alarmState")
                        .label("Extended Occupancy Fault State")
                        .definition(EXTENDED_OCCUPANCY_ALARM_STATE_DEFINITION)
                        .description("Clear or Fault - Extended Occupancy")
                        .addAllowedValues(CLEAR_ALARM_STATE, FAULT_EXTENDED_OCCUPANCY_STATE))
                .build();
        recordEncoding = new TextEncodingImpl(",", "\n");
    }

    public int getExtendedOccupancyThresholdMinutes() {
        return extendedOccupancyThresholdMinutes;
    }

    public void setExtendedOccupancyThresholdMinutes(int thresholdMinutes) {
        this.extendedOccupancyThresholdMinutes = Math.max(
                LaneConfig.MIN_EXTENDED_OCCUPANCY_THRESHOLD_MINUTES,
                Math.min(LaneConfig.MAX_EXTENDED_OCCUPANCY_THRESHOLD_MINUTES, thresholdMinutes));
    }

    public void publish(Instant sampleTime, boolean isOccupied, Instant occupancyStartTime) {
        publish(sampleTime, isOccupied, occupancyStartTime, false);
    }

    public void publish(Instant sampleTime, boolean isOccupied, Instant occupancyStartTime, boolean extendedOccupancyFault) {
        var dataBlock = latestRecord == null ? recordStruct.createDataBlock() : latestRecord.renew();
        long eventTime = sampleTime.toEpochMilli();
        boolean faultActive = isOccupied && extendedOccupancyFault;

        dataBlock.setDoubleValue(0, toEpochSeconds(sampleTime));
        dataBlock.setBooleanValue(1, isOccupied);
        dataBlock.setDoubleValue(2, isOccupied && occupancyStartTime != null
                ? toEpochSeconds(occupancyStartTime)
                : 0.0);
        dataBlock.setIntValue(3, extendedOccupancyThresholdMinutes);
        dataBlock.setBooleanValue(4, faultActive);
        dataBlock.setStringValue(5, faultActive ? FAULT_EXTENDED_OCCUPANCY_STATE : CLEAR_ALARM_STATE);

        latestRecord = dataBlock;
        latestRecordTime = eventTime;
        eventHandler.publish(new DataEvent(eventTime, this, dataBlock));
    }

    private static double toEpochSeconds(Instant instant) {
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    @Override
    public DataComponent getRecordDescription() {
        return recordStruct;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {
        return recordEncoding;
    }
}
