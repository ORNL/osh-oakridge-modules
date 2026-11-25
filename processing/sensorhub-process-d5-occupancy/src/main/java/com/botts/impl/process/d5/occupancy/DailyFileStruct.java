package com.botts.impl.process.d5.occupancy;

import net.opengis.swe.v20.Boolean;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.Time;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.vast.data.TextEncodingImpl;

public class DailyFileStruct {

    static final RADHelper radHelper = new RADHelper();
    public static final String DAILY_FILE_NAME = "dailyFile";
    public static final String DAILY_FILE_LABEL = "Daily File";
    public static final String OCCUPIED_NAME = "isOccupied";
    public static final String OCCUPIED_LABEL = "Is Occupied";
    public static final String OCCUPANCY_START_TIME_NAME = "occupancyStartTime";
    public static final String ALARM_NAME = "isAlarming";
    public static final String ALARM_LABEL = "Is Alarming";

    static Time samplingTime = radHelper.createPrecisionTimeStamp();
    static Boolean occupied = radHelper.createBoolean()
            .name(OCCUPIED_NAME)
            .label(OCCUPIED_LABEL)
            .definition(RADHelper.getRadUri("D5OccupancyStatus"))
            .value(false)
            .build();
    static Time occupancyStartTime = radHelper.createTime()
            .asPhenomenonTimeIsoUTC()
            .name(OCCUPANCY_START_TIME_NAME)
            .label("Occupancy Start Time")
            .definition(RADHelper.getRadUri("OccupancyStartTime"))
            .build();
    static Boolean rpmMsg = radHelper.createBoolean().name(ALARM_NAME).label(ALARM_LABEL).value(false).build();

    static final DataRecord dataStruct = radHelper.createRecord()
            .name(DAILY_FILE_NAME)
            .label(DAILY_FILE_LABEL)
            .updatable(true)
            .definition(RADHelper.getRadUri("DailyFile"))
            .addField(samplingTime.getName(), samplingTime)
            .addField(occupied.getName(), occupied)
            .addField(occupancyStartTime.getName(), occupancyStartTime)
            .addField(rpmMsg.getName(), rpmMsg)
            .build();

    static final DataEncoding dataEncoding = new TextEncodingImpl(",", "\n");

    public static DataComponent getRecordDescription() {
        return dataStruct.copy();
    }

    public static DataEncoding getRecommendedEncoding() {
        return dataEncoding;
    }
}
