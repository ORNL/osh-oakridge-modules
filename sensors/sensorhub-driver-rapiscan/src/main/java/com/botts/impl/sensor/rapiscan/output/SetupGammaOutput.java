package com.botts.impl.sensor.rapiscan.output;

import com.botts.impl.sensor.rapiscan.RapiscanSensor;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.vast.data.TextEncodingImpl;

public class SetupGammaOutput extends AbstractSensorOutput<RapiscanSensor> {

    private static final String SENSOR_OUTPUT_NAME = "setupGamma";
    private static final String SENSOR_OUTPUT_LABEL = "Setup Gamma";

    protected DataRecord dataStruct;
    protected DataEncoding dataEncoding;
    protected DataBlock dataBlock;

    int intervals;
    int occupancyHoldin;
    double nsigma;
    String algorithm;

    public SetupGammaOutput(RapiscanSensor parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);
    }

    public void init(){
        RADHelper radHelper = new RADHelper();

        var samplingTime = radHelper.createPrecisionTimeStamp();

        var highBGFault = radHelper.createHighBackgroundFault();
        var lowBGFault = radHelper.createLowBackgroundFault();
        var intervals = radHelper.createIntervals();
        var holdin = radHelper.createOccupancyHoldin();
        var nSigma = radHelper.createNSigma();

        var detectors = radHelper.createDetectors();
        var controlLLD = radHelper.createMasterLLD();
        var controlULD = radHelper.createMasterULD();
        var relayOutput = radHelper.createRelayOutput();
        var algorithm = radHelper.createAlgorithm();
        var softwareVersion = radHelper.createSoftwareVersion();

        var slaveLLD = radHelper.createSlaveLLD();
        var saveULD = radHelper.createSlaveULD();
        var backgroundTime = radHelper.createBackgroundTime();
        var backgroundSigma = radHelper.createBackgroundSigma();
        var firmwareVersion = radHelper.createFirmwareVersion();

        dataStruct = radHelper.createRecord()
                .name(getName())
                .label(SENSOR_OUTPUT_LABEL)
                .definition(RADHelper.getRadUri("setup-gammas"))
                .addField(samplingTime.getName(), samplingTime)
                .addField(highBGFault.getName(), highBGFault)
                .addField(lowBGFault.getName(), lowBGFault)
                .addField(intervals.getName(), intervals)
                .addField(holdin.getName(), holdin)
                .addField(nSigma.getName(), nSigma)

                .addField(detectors.getName(), detectors)
                .addField(controlLLD.getName(), controlLLD)
                .addField(controlULD.getName(), controlULD)
                .addField(relayOutput.getName(), relayOutput)
                .addField(algorithm.getName(), algorithm)
                .addField(softwareVersion.getName(), softwareVersion)

                .addField(slaveLLD.getName(), slaveLLD) //slave lld
                .addField(saveULD.getName(), saveULD) //slave uld
                .addField(backgroundTime.getName(), backgroundTime)
                .addField(backgroundSigma.getName(), backgroundSigma)
                .addField(firmwareVersion.getName(), firmwareVersion)
                .build();

        dataEncoding = new TextEncodingImpl(",", "\n");
    }

    public void onNewMessage(String[] setup1, String [] setup2, String[] setup3){
        if (setup1 == null || setup1.length < 6
                || setup2 == null || setup2.length < 7
                || setup3 == null || setup3.length < 6) {
            return;
        }
        if (latestRecord == null) {

            dataBlock = dataStruct.createDataBlock();

        } else {

            dataBlock = latestRecord.renew();
        }
        int highBg = Integer.parseInt(setup1[1].trim());
        int lowBg = Integer.parseInt(setup1[2].trim());
        int parsedIntervals = Integer.parseInt(setup1[3].trim());
        int parsedHoldin = Integer.parseInt(setup1[4].trim());
        double parsedNsigma = Double.parseDouble(setup1[5].trim());

        String detectors = setup2[1].trim();
        double controlLowDiscrim = Double.parseDouble(setup2[2].trim());
        double controlHighDiscrim = Double.parseDouble(setup2[3].trim());
        int relayOut = Integer.parseInt(setup2[4].trim());
        String parsedAlgorithm = setup2[5].trim();
        String softwareVer = setup2[6].trim();

        double slaveLowDiscrim = Double.parseDouble(setup3[1].trim());
        double slaveHighDiscrim = Double.parseDouble(setup3[2].trim());
        int bgTime = Integer.parseInt(setup3[3].trim());
        double bgSigma = Double.parseDouble(setup3[4].trim());
        String firmwareVer = setup3[5].trim();

        int index = 0;
        dataBlock.setLongValue(index++, System.currentTimeMillis() / 1000);
        // set up 1
        dataBlock.setIntValue(index++, highBg); //high bg
        dataBlock.setIntValue(index++, lowBg); // low bg
        dataBlock.setIntValue(index++, parsedIntervals); //intervals
        dataBlock.setIntValue(index++, parsedHoldin); //occupancy holding
        dataBlock.setDoubleValue(index++, parsedNsigma); //nVal
//        dataBlock.setStringValue(index++, setup1[6]); //placeholder

        //set up 2
        dataBlock.setStringValue(index++, detectors); //detectors
        dataBlock.setDoubleValue(index++, controlLowDiscrim); //low discrim
        dataBlock.setDoubleValue(index++, controlHighDiscrim); //high discrim
        dataBlock.setIntValue(index++, relayOut); //relay out
        dataBlock.setStringValue(index++, parsedAlgorithm); //algorithm
        dataBlock.setStringValue(index++, softwareVer); //version software

        //set up 3
        dataBlock.setDoubleValue(index++, slaveLowDiscrim); //low discrim
        dataBlock.setDoubleValue(index++, slaveHighDiscrim); //high discrim
        dataBlock.setIntValue(index++, bgTime); //background time
        dataBlock.setDoubleValue(index++, bgSigma); //background sigma
        dataBlock.setStringValue(index, firmwareVer); //version of firmware

        //gamma config values!
        intervals = parsedIntervals;
        occupancyHoldin = parsedHoldin;
        algorithm = parsedAlgorithm;
        nsigma = parsedNsigma;

        if (parentSensor != null) {
            parentSensor.onSetupGammaReceived(setup1, setup2, setup3);
        }

        latestRecord = dataBlock;
        eventHandler.publish(new DataEvent(System.currentTimeMillis(), SetupGammaOutput.this, dataBlock));

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
