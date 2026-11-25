package com.botts.impl.sensor.rs350.output;

import com.botts.impl.sensor.rs350.MessageHandler;
import com.botts.impl.sensor.rs350.RS350Sensor;
import com.botts.impl.sensor.rs350.messages.RS350Message;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.vast.data.TextEncodingImpl;

/**
 * Publishes the RS-350's GPS fix as a standard {@code location} datastream
 * matching the OSCAR viewer's {@code sensorLocation} LLA schema when enabled
 * for mobile/backpack deployments.
 */
public class LocationOutput extends AbstractSensorOutput<RS350Sensor> implements MessageHandler.ForegroundListener {
    public static final String SENSOR_OUTPUT_NAME = "location";
    private static final String SENSOR_OUTPUT_LABEL = "Location";

    protected DataRecord dataStruct;
    protected DataRecord liveDataStruct;
    protected DataEncoding dataEncoding;

    public LocationOutput(RS350Sensor parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);
    }

    public void init() {
        RADHelper radHelper = new RADHelper();

        var samplingTime = radHelper.createPrecisionTimeStamp();

        dataStruct = radHelper.createRecord()
                .name(getName())
                .label(SENSOR_OUTPUT_LABEL)
                .definition(RADHelper.getRadUri("location-output"))
                .addField(samplingTime.getName(), samplingTime)
                .addField("sensorLocation", radHelper.createLocationVectorLLA()
                        .label("Sensor Location"))
                .build();

        liveDataStruct = dataStruct.copy();
        dataEncoding = new TextEncodingImpl(",", "\n");
    }

    @Override
    public void onNewMessage(RS350Message message) {
        if (message == null) {
            return;
        }
        var foreground = message.getRs350ForegroundMeasurement();
        if (foreground == null) {
            return;
        }

        Double lat = foreground.getLat();
        Double lon = foreground.getLon();
        Double alt = foreground.getAlt();

        if (lat == null || lon == null
                || !Double.isFinite(lat) || !Double.isFinite(lon)
                || (lat == 0.0 && lon == 0.0)) {
            return;
        }

        DataRecord workingStruct = liveDataStruct != null ? liveDataStruct : dataStruct;
        DataBlock dataBlock = latestRecord == null
                ? workingStruct.createDataBlock()
                : latestRecord.renew();

        long fixTimeMillis = foreground.getStartDateTime() != null
                ? foreground.getStartDateTime()
                : System.currentTimeMillis();

        int index = 0;
        dataBlock.setDoubleValue(index++, fixTimeMillis / 1000.0);
        dataBlock.setDoubleValue(index++, lat);
        dataBlock.setDoubleValue(index++, lon);
        dataBlock.setDoubleValue(index, alt != null && Double.isFinite(alt) ? alt : 0.0);

        latestRecord = dataBlock;
        latestRecordTime = fixTimeMillis;

        eventHandler.publish(new DataEvent(latestRecordTime, LocationOutput.this, dataBlock));
    }

    @Override
    public DataComponent getRecordDescription() {
        return dataStruct;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {
        return dataEncoding;
    }

    @Override
    public double getAverageSamplingPeriod() {
        return 1.0;
    }
}
