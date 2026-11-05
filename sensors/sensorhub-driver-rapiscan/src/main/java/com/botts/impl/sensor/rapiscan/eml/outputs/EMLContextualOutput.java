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

public class EMLContextualOutput extends AbstractSensorOutput<RapiscanSensor> {

    private static final String SENSOR_OUTPUT_NAME = "emlContextual";
    private static final String SENSOR_OUTPUT_LABEL = "EML ERNIE Contextual";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "EML ERNIE Contextual Data parsed from XML";

    protected DataRecord dataStruct;
    protected DataEncoding dataEncoding;

    int versionIdIndex, modelIdIndex, thresholdsIndex;

    public EMLContextualOutput(RapiscanSensor parentSensor) {
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
                .definition(RADHelper.getRadUri("EMLContext"))
                .description(SENSOR_OUTPUT_DESCRIPTION);

        int fieldIndex = 0;

        dataRecordBuilder.addField(VERSION_ID_FIELD_NAME, emlFieldFactory.createVersionIdField());
        versionIdIndex = fieldIndex++;
        dataRecordBuilder.addField(MODEL_ID_FIELD_NAME, emlFieldFactory.createModelIdField());
        modelIdIndex = fieldIndex++;
        dataRecordBuilder.addField(THRESHOLDS_FIELD_NAME, emlFieldFactory.createThresholdsField());
        thresholdsIndex = fieldIndex;

        return dataRecordBuilder.build();
    }

    public void handleContextualMessage(EmlEvaluationResult results) {
        DataBlock dataBlock;
        if (latestRecord == null) {
            dataBlock = dataStruct.createDataBlock();
        } else {
            dataBlock = latestRecord.renew();
        }

        dataBlock.setStringValue(versionIdIndex, results.versionId());
        dataBlock.setStringValue(modelIdIndex, results.modelId());
        dataBlock.setDoubleValue(thresholdsIndex, results.primaryThreshold());

        latestRecord = dataBlock;
        eventHandler.publish(new DataEvent(System.currentTimeMillis(), EMLContextualOutput.this, dataBlock));
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
