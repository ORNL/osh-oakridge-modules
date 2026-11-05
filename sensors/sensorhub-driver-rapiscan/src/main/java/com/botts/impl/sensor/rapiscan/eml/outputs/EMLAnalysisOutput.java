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
import org.sensorhub.impl.utils.rad.eml.EmlSourceBreakdown;
import org.vast.data.DataArrayImpl;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.SWEBuilders;

import static com.botts.impl.sensor.rapiscan.eml.EMLFieldFactory.*;


public class EMLAnalysisOutput extends AbstractSensorOutput<RapiscanSensor> {
    private static final String SENSOR_OUTPUT_NAME = "emlAnalysis";
    private static final String SENSOR_OUTPUT_LABEL = "EML ERNIE Analysis";
    private static final String SENSOR_OUTPUT_DESCRIPTION = "EML ERNIE Analysis Data parsed from XML";

    protected DataRecord dataStruct;
    protected DataEncoding dataEncoding;

    public EMLAnalysisOutput(RapiscanSensor parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);
    }

    public void init() {
        dataStruct = getDataRecord();
        dataEncoding = new TextEncodingImpl(",", "\n");
    }

    private DataRecord getDataRecord() {
        var emlFieldFactory = new EMLFieldFactory();

        SWEBuilders.DataRecordBuilder dataRecordBuilder = emlFieldFactory.createRecord()
                .name(getName())
                .label(SENSOR_OUTPUT_LABEL)
                .definition(RADHelper.getRadUri("EMLAnalysis"))
                .description(SENSOR_OUTPUT_DESCRIPTION);

        dataRecordBuilder.addField(RESULT_FIELD_NAME, emlFieldFactory.createResultsField());
        dataRecordBuilder.addField(INVESTIGATIVE_PROBABILITY_FIELD_NAME, emlFieldFactory.createInvestigativeProbabilityField());
        dataRecordBuilder.addField(RELEASE_PROBABILITY_FIELD_NAME, emlFieldFactory.createReleaseProbabilityField());
        dataRecordBuilder.addField(GAMMA_ALERT_FIELD_NAME, emlFieldFactory.createErnieGammaAlertField());
        dataRecordBuilder.addField(NEUTRON_ALERT_FIELD_NAME, emlFieldFactory.createErnieNeutronAlertField());

        dataRecordBuilder.addField(VEHICLE_CLASS_FIELD_NAME, emlFieldFactory.createVehicleClassField());
        dataRecordBuilder.addField(VEHICLE_LENGTH_FIELD_NAME, emlFieldFactory.createVehicleLengthField());
        dataRecordBuilder.addField(YELLOW_LIGHT_MESSAGE_FIELD_NAME, emlFieldFactory.createYellowLightMessageField());

        dataRecordBuilder.addField("sourceCount", emlFieldFactory.createCount().id("sourceCountId"));

        SWEBuilders.DataRecordBuilder sourceArrayBuilder = emlFieldFactory.createRecord();
        sourceArrayBuilder.addField(SOURCE_TYPE_FIELD_NAME, emlFieldFactory.createSourceTypeField());
        sourceArrayBuilder.addField(CLASSIFIER_FIELD_NAME, emlFieldFactory.createClassifierUsedField());
        sourceArrayBuilder.addField(X_LOCATION_1_FIELD_NAME, emlFieldFactory.createXLocation1Field());
        sourceArrayBuilder.addField(X_LOCATION_2_FIELD_NAME, emlFieldFactory.createXLocation2Field());
        sourceArrayBuilder.addField(Y_LOCATION_FIELD_NAME, emlFieldFactory.createYLocationField());
        sourceArrayBuilder.addField(Z_LOCATION_FIELD_NAME, emlFieldFactory.createZLocationField());
        sourceArrayBuilder.addField(PROBABILITY_NON_EMITTING_FIELD_NAME, emlFieldFactory.createProbabilityNonEmittingField());
        sourceArrayBuilder.addField(PROBABILITY_NORM_FIELD_NAME, emlFieldFactory.createProbabilityNormField());
        sourceArrayBuilder.addField(PROBABILITY_THREAT_FIELD_NAME, emlFieldFactory.createProbabilityThreatField());
        dataRecordBuilder.addField("sources", emlFieldFactory.createArray()
                .withVariableSize("sourceCountId")
                .withElement("source", sourceArrayBuilder));

        dataRecordBuilder.addField(OVERALL_SOURCE_TYPE_FIELD_NAME, emlFieldFactory.createOverallSourceTypeField());
        dataRecordBuilder.addField(OVERALL_CLASSIFIER_FIELD_NAME, emlFieldFactory.createOverallClassifierUsedField());
        dataRecordBuilder.addField(OVERALL_X_LOCATION_1_FIELD_NAME, emlFieldFactory.createOverallXLocation1Field());
        dataRecordBuilder.addField(OVERALL_X_LOCATION_2_FIELD_NAME, emlFieldFactory.createOverallXLocation2Field());
        dataRecordBuilder.addField(OVERALL_Y_LOCATION_FIELD_NAME, emlFieldFactory.createOverallYLocationField());
        dataRecordBuilder.addField(OVERALL_Z_LOCATION_FIELD_NAME, emlFieldFactory.createOverallZLocationField());
        dataRecordBuilder.addField(OVERALL_PROBABILITY_NON_EMITTING_FIELD_NAME, emlFieldFactory.createOverallProbabilityNonEmittingField());
        dataRecordBuilder.addField(OVERALL_PROBABILITY_NORM_FIELD_NAME, emlFieldFactory.createOverallProbabilityNormField());
        dataRecordBuilder.addField(OVERALL_PROBABILITY_THREAT_FIELD_NAME, emlFieldFactory.createOverallProbabilityThreatField());
        return dataRecordBuilder.build();
    }

    public void handleAnalysisMessage(EmlEvaluationResult results) {
        dataStruct = getDataRecord();
        DataBlock dataBlock = dataStruct.createDataBlock();
        dataStruct.setData(dataBlock);

        int index = 0;
        dataBlock.setStringValue(index++, results.result());
        dataBlock.setDoubleValue(index++, results.investigateProbability());
        dataBlock.setDoubleValue(index++, results.releaseProbability());
        dataBlock.setBooleanValue(index++, results.ernieGammaAlert());
        dataBlock.setBooleanValue(index++, results.ernieNeutronAlert());

        dataBlock.setIntValue(index++, results.vehicleClass());
        dataBlock.setDoubleValue(index++, results.vehicleLength());
        dataBlock.setStringValue(index++, results.yellowLightMessage());

        int sourceCount = results.sources().size();
        dataBlock.setIntValue(index++, sourceCount);

        var array = ((DataArrayImpl) dataStruct.getComponent("sources"));
        array.updateSize();
        dataBlock.updateAtomCount();

        for (int i = 0; i < sourceCount; i++) {
            EmlSourceBreakdown src = results.sources().get(i);
            dataBlock.setStringValue(index++, src.sourceType());
            dataBlock.setStringValue(index++, src.classifierUsed());
            dataBlock.setDoubleValue(index++, src.xLocation1());
            dataBlock.setDoubleValue(index++, src.xLocation2());
            dataBlock.setDoubleValue(index++, src.yLocation());
            dataBlock.setDoubleValue(index++, src.zLocation());
            dataBlock.setDoubleValue(index++, src.probabilityNonEmitting());
            dataBlock.setDoubleValue(index++, src.probabilityNorm());
            dataBlock.setDoubleValue(index++, src.probabilityThreat());
        }

        EmlSourceBreakdown overall = results.overallSource();
        if (overall != null) {
            dataBlock.setStringValue(index++, overall.sourceType());
            dataBlock.setStringValue(index++, overall.classifierUsed());
            dataBlock.setDoubleValue(index++, overall.xLocation1());
            dataBlock.setDoubleValue(index++, overall.xLocation2());
            dataBlock.setDoubleValue(index++, overall.yLocation());
            dataBlock.setDoubleValue(index++, overall.zLocation());
            dataBlock.setDoubleValue(index++, overall.probabilityNonEmitting());
            dataBlock.setDoubleValue(index++, overall.probabilityNorm());
            dataBlock.setDoubleValue(index, overall.probabilityThreat());
        } else {
            String defaultType = "RELEASE".equalsIgnoreCase(results.result()) ? "NonEmitting" : "NONE";
            dataBlock.setStringValue(index++, defaultType);
            dataBlock.setStringValue(index++, results.modelId());
            dataBlock.setDoubleValue(index++, 0.0);
            dataBlock.setDoubleValue(index++, 0.0);
            dataBlock.setDoubleValue(index++, 0.0);
            dataBlock.setDoubleValue(index++, 0.0);
            dataBlock.setDoubleValue(index++, results.releaseProbability());
            dataBlock.setDoubleValue(index++, 0.0);
            dataBlock.setDoubleValue(index, results.investigateProbability());
        }

        latestRecord = dataBlock;
        eventHandler.publish(new DataEvent(System.currentTimeMillis(), EMLAnalysisOutput.this, dataBlock));
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
