package com.botts.impl.sensor.rapiscan.eml.outputs;

import com.botts.impl.sensor.rapiscan.RapiscanSensor;
import com.botts.impl.sensor.rapiscan.eml.EMLFieldFactory;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.eml.EmlEvaluationResult;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.SWEBuilders;

import static com.botts.impl.sensor.rapiscan.eml.EMLFieldFactory.*;

public class EMLScanContextualOutput extends AbstractSensorOutput<RapiscanSensor> {

    private static final String SENSOR_OUTPUT_NAME = "emlScanContextual";
    private static final String SENSOR_OUTPUT_LABEL = "EML Scan Contextual";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "EML ERNIE Contextual Data parsed from XML";

    protected DataRecord dataStruct;
    protected DataEncoding dataEncoding;

    int portIdIndex, laneIdIndex, dateTimeIndex, rpmGammaAlertIndex, rpmNeutronAlertIndex, segmentIdIndex, rpmResultIndex, rpmScanErrorIndex;

    public EMLScanContextualOutput(RapiscanSensor parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);
    }

    public void init() {
        dataStruct = getDataRecord();
        dataEncoding = new TextEncodingImpl(",", "\n");
    }

    DataRecord getDataRecord() {
        var emlFieldFactory = new EMLFieldFactory();

        SWEBuilders.DataRecordBuilder dataRecordBuilder = emlFieldFactory.createRecord()
                .name(getName())
                .label(SENSOR_OUTPUT_LABEL)
                .definition(RADHelper.getRadUri("EMLScan"))
                .description(SENSOR_OUTPUT_DESCRIPTION);

        int fieldIndex = 0;

        dataRecordBuilder.addField(PORT_ID_FIELD_NAME, emlFieldFactory.createPortIdField());
        portIdIndex = fieldIndex++;

        dataRecordBuilder.addField(LANE_ID_FIELD_NAME, emlFieldFactory.createLaneIdField());
        laneIdIndex = fieldIndex++;

        dataRecordBuilder.addField(TIME_DATE_FIELD_NAME, emlFieldFactory.createDateTimeField());
        dateTimeIndex = fieldIndex++;

        dataRecordBuilder.addField(SEGMENT_ID_FIELD_NAME, emlFieldFactory.createSegmentIdField());
        segmentIdIndex = fieldIndex++;

        dataRecordBuilder.addField(RPM_RESULT_FIELD_NAME, emlFieldFactory.createRpmResultField());
        rpmResultIndex = fieldIndex++;

        dataRecordBuilder.addField(RPM_GAMMA_ALERT_FIELD_NAME, emlFieldFactory.createRpmGammaAlertField());
        rpmGammaAlertIndex = fieldIndex++;

        dataRecordBuilder.addField(RPM_NEUTRON_ALERT_FIELD_NAME, emlFieldFactory.createRpmNeutronAlertField());
        rpmNeutronAlertIndex = fieldIndex++;

        dataRecordBuilder.addField(RPM_SCAN_ERROR_FIELD_NAME, emlFieldFactory.createRpmScanErrorField());
        rpmScanErrorIndex = fieldIndex;

        return dataRecordBuilder.build();
    }

    public void handleScanContextualMessage(EmlEvaluationResult results) {
        DataBlock dataBlock;
        if (latestRecord == null) {
            dataBlock = dataStruct.createDataBlock();
        } else {
            dataBlock = latestRecord.renew();
        }

        dataBlock.setStringValue(portIdIndex, results.portId());
        dataBlock.setLongValue(laneIdIndex, results.laneId());
        dataBlock.setLongValue(dateTimeIndex, results.dateTimeEpochMillis());
        dataBlock.setLongValue(segmentIdIndex, results.segmentId());
        dataBlock.setStringValue(rpmResultIndex, results.rpmResult());
        dataBlock.setBooleanValue(rpmGammaAlertIndex, results.rpmGammaAlert());
        dataBlock.setBooleanValue(rpmNeutronAlertIndex, results.rpmNeutronAlert());
        dataBlock.setBooleanValue(rpmScanErrorIndex, results.rpmScanError());

        latestRecord = dataBlock;
        eventHandler.publish(new DataEvent(System.currentTimeMillis(), EMLScanContextualOutput.this, dataBlock));
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
        return 0;
    }
}
