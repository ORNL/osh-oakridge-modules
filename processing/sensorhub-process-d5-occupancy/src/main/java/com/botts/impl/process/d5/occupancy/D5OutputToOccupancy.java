/*******************************************************************************

  The contents of this file are subject to the Mozilla Public License, v. 2.0.
  If a copy of the MPL was not distributed with this file, You can obtain one
  at http://mozilla.org/MPL/2.0/.

  Software distributed under the License is distributed on an "AS IS" basis,
  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
  for the specific language governing rights and limitations under the License.

  The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
  Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.

 ******************************************************************************/

package com.botts.impl.process.d5.occupancy;

import net.opengis.sensorml.v20.IOPropertyList;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.Text;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsEvent;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.processing.OSHProcessInfo;
import org.sensorhub.impl.processing.ISensorHubProcess;
import org.sensorhub.impl.sensor.SensorSystem;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.model.OccupancyExtended;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.sensorhub.utils.Async;
import org.slf4j.Logger;
import org.vast.process.ExecutableProcessImpl;
import org.vast.swe.SWEHelper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Turns Kromek D5 alarm activity into {@link OccupancyExtended} observations
 * and live {@code dailyFile} state transitions for the OSCAR event table and
 * adjudication workflow.
 *
 * <p>The D5 has no discrete portal occupancy gate; its 1 Hz
 * {@code KromekSerialRadiometricStatusReport} carries three live alarm flags
 * ({@code doseAlarmActive}, {@code gammaCpsAlarmActive},
 * {@code neutronCpsAlarmActive}). Rising edges open an episode, max gamma and
 * neutron counts are tracked from {@code KromekDetectorRadiometricsV1Report},
 * the first valid GPS fix in the episode is captured, and once all flags stay
 * clear beyond {@link #EPISODE_CLOSE_DELAY_MS} one {@link OccupancyExtended}
 * observation is published (subject to {@link #OCCUPANCY_INTERVAL_MS} cooldown).
 */
public class D5OutputToOccupancy extends ExecutableProcessImpl implements ISensorHubProcess {
    Logger logger = getLogger();
    public static final OSHProcessInfo INFO = new OSHProcessInfo(
            "d5alarmrecorder", "Kromek D5 alarm data recording process", null, D5OutputToOccupancy.class);
    ISensorHub hub;
    Text systemInputParam;
    String inputSystemID;
    private static final RADHelper radHelper = new RADHelper();

    public static final double DAILYFILE_INTERVAL_MS = 500;
    public static final long OCCUPANCY_INTERVAL_MS = 10_000L;
    public static final long EPISODE_CLOSE_DELAY_MS = 3_000L;
    public static final String SYSTEM_INPUT_PARAM = "systemInput";
    public static final OccupancyOutput occupancyOutput = new OccupancyOutput(new SensorSystem());

    public static final String OCCUPANCY_NAME = occupancyOutput.getName();
    public static final String RADIOMETRIC_STATUS_NAME = "KromekSerialRadiometricStatusReport";
    public static final String RADIOMETRICS_NAME = "KromekDetectorRadiometricsV1Report";

    public static final List<String> ALL_OUTPUTS = List.of(RADIOMETRIC_STATUS_NAME, RADIOMETRICS_NAME);
    public static final List<String> CHAIN_INPUTS = List.of(RADIOMETRIC_STATUS_NAME);

    public final List<Flow.Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final IOPropertyList allInputs = new IOPropertyList();

    private final Object lock = new Object();

    // Episode state (guarded by lock)
    private boolean inEpisode = false;
    private boolean hasReceivedSample = false;
    private boolean liveStateChanged = false;
    private long episodeStartMillis = 0L;
    private long lastAlarmActiveMillis = 0L;
    private boolean sawGammaAlarm = false;
    private boolean sawNeutronAlarm = false;
    private double episodeLat = 0.0;
    private double episodeLon = 0.0;
    private int episodeMaxGamma = 0;
    private int episodeMaxNeutron = 0;

    private int occupancyCount = 0;
    private double latestNeutronBackground = 0.0;

    private OccupancyExtended occupancy;
    private volatile boolean doPublishOccupancy = false;
    private volatile long lastOccupancyPublish = 0L;
    private long lastDailyFileTime = 0L;

    private boolean disposed = false;
    private boolean hasStarted = false;

    public D5OutputToOccupancy() {
        super(INFO);

        paramData.add(SYSTEM_INPUT_PARAM, systemInputParam = radHelper.createText()
                .label("Parent System Input")
                .description("Parent system (Lane) that contains one Kromek D5 subsystem.")
                .definition(SWEHelper.getPropertyUri("System"))
                .value("")
                .build());

        outputData.add(OccupancyOutput.NAME, OccupancyExtended.createExtendedRecordStructure());
        outputData.add(DailyFileStruct.DAILY_FILE_NAME, DailyFileStruct.getRecordDescription());
    }

    @Override
    public void notifyParamChange() {
        super.notifyParamChange();
        inputSystemID = systemInputParam.getData().getStringValue();
        synchronized (lock) {
            disposed = false;
            inEpisode = false;
            hasReceivedSample = false;
            liveStateChanged = false;
        }

        if (!Objects.equals(inputSystemID, "")) {
            try {
                Async.waitForCondition(() -> checkDataStreamInput(ALL_OUTPUTS.toArray(new String[0])), 500, 10_000);
            } catch (TimeoutException e) {
                if (processInfo == null) {
                    throw new IllegalStateException("D5 data stream " + inputSystemID + " not found", e);
                } else {
                    throw new IllegalStateException("D5 data stream " + inputSystemID + " has no data", e);
                }
            }
        }
        for (var output : outputData) {
            ((DataComponent) output).renewDataBlock();
        }
    }

    private void setOccupancyOutput(OccupancyExtended occupancy) {
        ((DataComponent) outputData.get(OCCUPANCY_NAME))
                .setData(OccupancyExtended.fromOccupancy(occupancy));
    }

    private boolean checkDataStreamInput(String... dataStreamNames) {
        inputData.clear();
        allInputs.clear();

        for (var subscription : subscriptions) {
            subscription.cancel();
        }
        subscriptions.clear();

        var sysFilter = new SystemFilter.Builder()
                .withUniqueIDs(inputSystemID)
                .includeMembers(true)
                .build();

        var db = hub.getDatabaseRegistry().getFederatedDatabase();

        for (String dataStreamName : dataStreamNames) {
            List<IDataStreamInfo> dataStreams;
            try (var dataStreamQuery = db.getDataStreamStore().select(new DataStreamFilter.Builder()
                    .withSystems(sysFilter)
                    .withCurrentVersion()
                    .withOutputNames(dataStreamName)
                    .withLimit(1)
                    .build())) {
                dataStreams = dataStreamQuery.collect(Collectors.toList());
            }
            if (!dataStreams.isEmpty()) {
                if (CHAIN_INPUTS.contains(dataStreamName)) {
                    inputData.add(dataStreamName, dataStreams.get(0).getRecordStructure().copy());
                }

                allInputs.add(dataStreamName, dataStreams.get(0).getRecordStructure().copy());

                hub.getEventBus().newSubscription(ObsEvent.class)
                        .withTopicID(EventUtils.getDataStreamDataTopicID(inputSystemID, dataStreamName))
                        .subscribe(this::processDataEvent)
                        .thenAccept(subscription -> {
                            synchronized (lock) {
                                if (disposed) {
                                    subscription.cancel();
                                    return;
                                }
                                subscriptions.add(subscription);
                            }
                            subscription.request(Long.MAX_VALUE);
                            logger.info("Started subscription to " + dataStreamName);
                        });
            }
        }

        for (var input : inputData) {
            ((DataComponent) input).renewDataBlock();
        }
        for (var input : allInputs) {
            ((DataComponent) input).renewDataBlock();
        }
        return !inputData.isEmpty();
    }

    private void processDataEvent(ObsEvent event) {
        String outputName = event.getOutputName();
        if (!ALL_OUTPUTS.contains(outputName)) {
            logger.warn("Received data event for unknown output: {}", outputName);
            return;
        }

        DataBlock[] obsResults = Arrays.stream(event.getObservations())
                .map(IObsData::getResult)
                .toArray(DataBlock[]::new);

        boolean publishLive = false;
        switch (outputName) {
            case RADIOMETRIC_STATUS_NAME -> {
                processRadiometricStatus(obsResults);
                publishLive = true;
            }
            case RADIOMETRICS_NAME -> processRadiometrics(obsResults);
            default -> logger.debug("(Default) Received data event for unknown output: {}", outputName);
        }

        if (publishLive) {
            try {
                publishData();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while publishing D5 live occupancy state");
            }
        }
    }

    private void processRadiometricStatus(DataBlock... eventRecords) {
        synchronized (lock) {
            DataComponent statusComponent = (DataComponent) allInputs.get(RADIOMETRIC_STATUS_NAME);
            if (statusComponent == null) {
                return;
            }
            for (DataBlock eventRecord : eventRecords) {
                statusComponent.setData(eventRecord);

                boolean doseAlarm = getBool(statusComponent, "doseAlarmActive");
                boolean gammaAlarm = getBool(statusComponent, "gammaCpsAlarmActive");
                boolean neutronAlarm = getBool(statusComponent, "neutronCpsAlarmActive");
                double lat = getDouble(statusComponent, "latitude");
                double lon = getDouble(statusComponent, "longitude");

                onRadiometricStatusSampleLocked(doseAlarm, gammaAlarm, neutronAlarm, lat, lon, System.currentTimeMillis());
            }
        }
    }

    /**
     * Package-private entry point for deterministic episode edge-detection unit tests.
     */
    OccupancyExtended onRadiometricStatusSample(
            boolean doseAlarm,
            boolean gammaAlarm,
            boolean neutronAlarm,
            double lat,
            double lon,
            long nowMillis) {
        synchronized (lock) {
            onRadiometricStatusSampleLocked(doseAlarm, gammaAlarm, neutronAlarm, lat, lon, nowMillis);
            if (doPublishOccupancy) {
                OccupancyExtended closed = occupancy;
                setOccupancyOutput(closed);
                doPublishOccupancy = false;
                lastOccupancyPublish = nowMillis;
                updateDailyFileComponentLocked(nowMillis);
                return closed;
            }
            updateDailyFileComponentLocked(nowMillis);
            return null;
        }
    }

    private void onRadiometricStatusSampleLocked(
            boolean doseAlarm,
            boolean gammaAlarm,
            boolean neutronAlarm,
            double lat,
            double lon,
            long nowMillis) {
        if (!hasReceivedSample) {
            hasReceivedSample = true;
            liveStateChanged = true;
        }
        boolean alarming = doseAlarm || gammaAlarm || neutronAlarm;

        if (alarming) {
            lastAlarmActiveMillis = nowMillis;
            if (!inEpisode) {
                if (lastOccupancyPublish > 0 && nowMillis - lastOccupancyPublish < OCCUPANCY_INTERVAL_MS) {
                    return;
                }
                inEpisode = true;
                liveStateChanged = true;
                episodeStartMillis = nowMillis;
                sawGammaAlarm = false;
                sawNeutronAlarm = false;
                episodeMaxGamma = 0;
                episodeMaxNeutron = 0;
                episodeLat = 0.0;
                episodeLon = 0.0;
                logger.info("D5 alarm episode started");
            }
            sawGammaAlarm |= gammaAlarm || doseAlarm;
            sawNeutronAlarm |= neutronAlarm;
            if (episodeLat == 0.0 && episodeLon == 0.0
                    && Double.isFinite(lat) && Double.isFinite(lon)
                    && (lat != 0.0 || lon != 0.0)) {
                episodeLat = lat;
                episodeLon = lon;
            }
        } else if (inEpisode && nowMillis - lastAlarmActiveMillis > EPISODE_CLOSE_DELAY_MS) {
            closeEpisodeLocked();
            liveStateChanged = true;
        }
    }

    private void closeEpisodeLocked() {
        String category;
        if (sawGammaAlarm && sawNeutronAlarm) {
            category = "Gamma-Neutron";
        } else if (sawNeutronAlarm) {
            category = "Neutron";
        } else {
            category = "Gamma";
        }

        var builder = new OccupancyExtended.Builder();
        if (episodeLat != 0.0 || episodeLon != 0.0) {
            builder.location(episodeLat, episodeLon);
        }
        builder.alarmCategory(category)
                .samplingTime(episodeStartMillis / 1000.0)
                .startTime(episodeStartMillis / 1000.0)
                .endTime(lastAlarmActiveMillis / 1000.0)
                .gammaAlarm(sawGammaAlarm)
                .neutronAlarm(sawNeutronAlarm)
                .maxGammaCount(episodeMaxGamma)
                .maxNeutronCount(episodeMaxNeutron)
                .neutronBackground(latestNeutronBackground)
                .occupancyCount(++occupancyCount);

        occupancy = builder.build();
        doPublishOccupancy = true;
        inEpisode = false;
        logger.info("D5 alarm episode closed ({}), publishing occupancy", category);
    }

    private void processRadiometrics(DataBlock... eventRecords) {
        synchronized (lock) {
            DataComponent radComponent = (DataComponent) allInputs.get(RADIOMETRICS_NAME);
            if (radComponent == null) {
                return;
            }
            for (DataBlock eventRecord : eventRecords) {
                radComponent.setData(eventRecord);

                int gammaCounts = (int) getDouble(radComponent, "gammaCounts");
                int neutronCounts = (int) getDouble(radComponent, "neutronCounts");
                double neutronLiveTimeMs = getDouble(radComponent, "neutronLiveTime");

                onRadiometricsSampleLocked(gammaCounts, neutronCounts, neutronLiveTimeMs);
            }
        }
    }

    void onRadiometricsSample(int gammaCounts, int neutronCounts, double neutronLiveTimeMs) {
        synchronized (lock) {
            onRadiometricsSampleLocked(gammaCounts, neutronCounts, neutronLiveTimeMs);
        }
    }

    private void onRadiometricsSampleLocked(int gammaCounts, int neutronCounts, double neutronLiveTimeMs) {
        if (inEpisode) {
            episodeMaxGamma = Math.max(episodeMaxGamma, gammaCounts);
            episodeMaxNeutron = Math.max(episodeMaxNeutron, neutronCounts);
        } else {
            double liveTimeS = neutronLiveTimeMs > 0 ? neutronLiveTimeMs / 1000.0 : 1.0;
            latestNeutronBackground = neutronCounts / liveTimeS;
        }
    }

    private static boolean getBool(DataComponent parent, String name) {
        var component = parent.getComponent(name);
        return component != null && component.getData().getBooleanValue();
    }

    private static double getDouble(DataComponent parent, String name) {
        var component = parent.getComponent(name);
        return component == null ? 0.0 : component.getData().getDoubleValue();
    }

    private void updateDailyFileComponentLocked(long nowMillis) {
        DataComponent dailyFile = outputData.getComponent(DailyFileStruct.DAILY_FILE_NAME);
        if (!dailyFile.hasData()) {
            dailyFile.renewDataBlock();
        }
        dailyFile.getComponent(DailyFileStruct.OCCUPIED_NAME).getData().setBooleanValue(inEpisode);
        dailyFile.getComponent(DailyFileStruct.OCCUPANCY_START_TIME_NAME).getData()
                .setDoubleValue(inEpisode ? episodeStartMillis / 1000.0 : 0.0);
        dailyFile.getComponent(DailyFileStruct.ALARM_NAME).getData().setBooleanValue(inEpisode);
        dailyFile.getComponent(DailyFileStruct.samplingTime.getName()).getData().setDoubleValue(nowMillis / 1000.0);
    }

    @Override
    protected void publishData() throws InterruptedException {
        synchronized (lock) {
            if (!hasReceivedSample) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean isDailyFileIntervalElapsed = now - DAILYFILE_INTERVAL_MS > lastDailyFileTime;

            if (!isDailyFileIntervalElapsed && !liveStateChanged && !doPublishOccupancy) {
                return;
            }

            if (doPublishOccupancy) {
                setOccupancyOutput(occupancy);
                doPublishOccupancy = false;
                lastOccupancyPublish = now;
                logger.info("Publishing occupancy output");
            }

            lastDailyFileTime = now;
            updateDailyFileComponentLocked(now);
            try {
                super.publishData();
                liveStateChanged = false;
            } catch (InterruptedException e) {
                logger.error("Error publishing daily file", e);
            }
        }
    }

    @Override
    public void execute() {
        if (inputSystemID == null) {
            inputSystemID = systemInputParam.getData().getStringValue();
        }
        if (!hasStarted) {
            hasStarted = true;
            notifyParamChange();
        }
    }

    @Override
    public void dispose() {
        synchronized (lock) {
            disposed = true;
            inEpisode = false;
            hasReceivedSample = false;
            liveStateChanged = false;
        }
        subscriptions.forEach(Flow.Subscription::cancel);
        subscriptions.clear();
        hasStarted = false;
    }

    public Map<String, DataEncoding> getOutputEncodingMap() {
        Map<String, DataEncoding> outputEncodingMap = new HashMap<>();
        outputEncodingMap.put(OCCUPANCY_NAME, occupancyOutput.getRecommendedEncoding());
        outputEncodingMap.put(DailyFileStruct.DAILY_FILE_NAME, DailyFileStruct.getRecommendedEncoding());
        return outputEncodingMap;
    }

    @Override
    public void setParentHub(ISensorHub hub) {
        this.hub = hub;
    }
}
