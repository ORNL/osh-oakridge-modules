/*
 * The contents of this file are subject to the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one
 * at http://mozilla.org/MPL/2.0/.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the License.
 *
 * Copyright (c) 2026 Botts Innovative Research, Inc. All Rights Reserved.
 */
package com.botts.impl.sensor.kromek.d5;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.vast.data.TextEncodingImpl;

/**
 * Publishes the Kromek D5's GPS fix (carried in {@code KromekSerialRadiometricStatusReport})
 * as a standard {@code location} datastream matching the OSCAR viewer's
 * {@code sensorLocation} LLA schema when enabled for mobile walker deployments.
 */
public class D5LocationOutput extends AbstractSensorOutput<D5Sensor> {
    public static final String SENSOR_OUTPUT_NAME = "location";
    private static final String SENSOR_OUTPUT_LABEL = "Location";

    protected DataRecord dataStruct;
    protected DataRecord liveDataStruct;
    protected DataEncoding dataEncoding;

    D5LocationOutput(D5Sensor parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);
    }

    void doInit() {
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

    public void onNewFix(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || (lat == 0.0 && lon == 0.0)) {
            return;
        }

        DataRecord workingStruct = liveDataStruct != null ? liveDataStruct : dataStruct;
        DataBlock dataBlock = latestRecord == null
                ? workingStruct.createDataBlock()
                : latestRecord.renew();

        long nowMillis = System.currentTimeMillis();

        int index = 0;
        dataBlock.setDoubleValue(index++, nowMillis / 1000.0);
        dataBlock.setDoubleValue(index++, lat);
        dataBlock.setDoubleValue(index++, lon);
        dataBlock.setDoubleValue(index, 0.0);

        latestRecord = dataBlock;
        latestRecordTime = nowMillis;

        eventHandler.publish(new DataEvent(latestRecordTime, D5LocationOutput.this, dataBlock));
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
