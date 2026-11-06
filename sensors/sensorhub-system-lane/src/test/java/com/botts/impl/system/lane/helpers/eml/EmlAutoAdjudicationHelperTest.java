package com.botts.impl.system.lane.helpers.eml;

import net.opengis.swe.v20.DataComponent;
import org.junit.Test;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsData;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.model.Occupancy;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.vast.data.TextEncodingImpl;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EmlAutoAdjudicationHelperTest {

    private static DataComponent createOccupancyRecordStructure() {
        RADHelper radHelper = new RADHelper();
        var samplingTime = radHelper.createPrecisionTimeStamp();
        var occupancyCount = radHelper.createOccupancyCount();
        var occupancyStart = radHelper.createOccupancyStartTime();
        var occupancyEnd = radHelper.createOccupancyEndTime();
        var neutronBackground = radHelper.createNeutronBackground();
        var gammaAlarm = radHelper.createGammaAlarm();
        var neutronAlarm = radHelper.createNeutronAlarm();
        var maxGamma = radHelper.createMaxGamma();
        var maxNeutron = radHelper.createMaxNeutron();
        var adjudicationIdsCount = radHelper.createAdjudicatedIdCount();
        var adjudicatedIds = radHelper.createAdjudicatedIdsArray();
        var videoPathCount = radHelper.createVideoPathCount();
        var videoPaths = radHelper.createVideoPathsArray();
        var webIdObsIdsCount = radHelper.createWebIdObsIdCount();
        var webIdObsIds = radHelper.createWebIdObsIdsArray();

        return radHelper.createRecord()
                .name(OccupancyOutput.NAME)
                .updatable(true)
                .addField(samplingTime.getName(), samplingTime)
                .addField(occupancyCount.getName(), occupancyCount)
                .addField(occupancyStart.getName(), occupancyStart)
                .addField(occupancyEnd.getName(), occupancyEnd)
                .addField(neutronBackground.getName(), neutronBackground)
                .addField(gammaAlarm.getName(), gammaAlarm)
                .addField(neutronAlarm.getName(), neutronAlarm)
                .addField(maxGamma.getName(), maxGamma)
                .addField(maxNeutron.getName(), maxNeutron)
                .addField(adjudicationIdsCount.getName(), adjudicationIdsCount)
                .addField(adjudicatedIds.getName(), adjudicatedIds)
                .addField(videoPathCount.getName(), videoPathCount)
                .addField(videoPaths.getName(), videoPaths)
                .addField(webIdObsIdsCount.getName(), webIdObsIdsCount)
                .addField(webIdObsIds.getName(), webIdObsIds)
                .build();
    }

    private static Occupancy buildOccupancy(int count, double samplingTime) {
        return new Occupancy.Builder()
                .samplingTime(samplingTime)
                .occupancyCount(count)
                .startTime(samplingTime - 12.0)
                .endTime(samplingTime)
                .neutronBackground(150.0)
                .gammaAlarm(true)
                .neutronAlarm(false)
                .maxGammaCount(702)
                .maxNeutronCount(4)
                .adjudicatedIds(List.of("eml-non-threat"))
                .videoPaths(List.of())
                .webIdObsIds(List.of())
                .build();
    }

    @Test
    public void resolvesBatchedOccupancyAfterCommitAndMicrosecondRounding() {
        String producerUid = "urn:osh:sensor:rapiscan:lane-50";
        BigId dsId = BigId.fromLong(1, 42L);
        DataComponent recordStruct = createOccupancyRecordStructure();
        IDataStreamInfo dsInfo = new DataStreamInfo.Builder()
                .withName(OccupancyOutput.NAME)
                .withSystem(new FeatureId(BigId.fromLong(1, 10L), producerUid))
                .withRecordDescription(recordStruct)
                .withRecordEncoding(new TextEncodingImpl(",", "\n"))
                .build();

        IDataStreamStore dsStore = (IDataStreamStore) Proxy.newProxyInstance(
                IDataStreamStore.class.getClassLoader(),
                new Class<?>[]{IDataStreamStore.class},
                (proxy, method, args) -> {
                    if ("get".equals(method.getName()) && args != null && args.length == 1) {
                        DataStreamKey key = (DataStreamKey) args[0];
                        return dsId.equals(key.getInternalID()) ? dsInfo : null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        // SWEDataUtils.toInstant(double) can carry sub-microsecond nanos, while
        // PostgreSQL rounds stored phenomenonTime to microsecond precision.
        Instant eventInstant = Instant.parse("2026-11-06T02:34:17.846999883Z");
        Instant storedInstant = Instant.parse("2026-11-06T02:34:17.847000Z");
        Instant neighborInstant = Instant.parse("2026-11-06T02:34:17.845000Z");

        Occupancy neighborOcc = buildOccupancy(15526, 1793932457.845);
        Occupancy targetOcc = buildOccupancy(15527, 1793932457.847);

        IObsData eventObs = new ObsData.Builder()
                .withDataStream(dsId)
                .withPhenomenonTime(eventInstant)
                .withResult(Occupancy.fromOccupancy(targetOcc))
                .build();

        IObsData storedNeighborObs = new ObsData.Builder()
                .withDataStream(dsId)
                .withPhenomenonTime(neighborInstant)
                .withResult(Occupancy.fromOccupancy(neighborOcc))
                .build();

        IObsData storedTargetObs = new ObsData.Builder()
                .withDataStream(dsId)
                .withPhenomenonTime(storedInstant)
                .withResult(Occupancy.fromOccupancy(targetOcc))
                .build();

        BigId neighborKey = BigId.fromLong(1, 100L);
        BigId expectedTargetKey = BigId.fromLong(1, 101L);

        AtomicBoolean committed = new AtomicBoolean(false);
        AtomicInteger commitCalls = new AtomicInteger(0);
        AtomicInteger selectCalls = new AtomicInteger(0);
        AtomicInteger closedStreams = new AtomicInteger(0);

        IObsStore batchedStore = (IObsStore) Proxy.newProxyInstance(
                IObsStore.class.getClassLoader(),
                new Class<?>[]{IObsStore.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "commit":
                            commitCalls.incrementAndGet();
                            committed.set(true);
                            return null;
                        case "getDataStreams":
                            return dsStore;
                        case "selectEntries":
                            selectCalls.incrementAndGet();
                            ObsFilter filter = (ObsFilter) args[0];
                            List<Map.Entry<BigId, IObsData>> visible = new ArrayList<>();
                            if (committed.get() && filter.getPhenomenonTime() != null) {
                                var range = filter.getPhenomenonTime().getRange();
                                if (!neighborInstant.isBefore(range.lowerEndpoint())
                                        && !neighborInstant.isAfter(range.upperEndpoint())) {
                                    visible.add(new AbstractMap.SimpleImmutableEntry<>(neighborKey, storedNeighborObs));
                                }
                                if (!storedInstant.isBefore(range.lowerEndpoint())
                                        && !storedInstant.isAfter(range.upperEndpoint())) {
                                    visible.add(new AbstractMap.SimpleImmutableEntry<>(expectedTargetKey, storedTargetObs));
                                }
                            }
                            return visible.stream().onClose(closedStreams::incrementAndGet);
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });

        BigId resolved = EmlAutoAdjudicationHelper.resolvePersistedObsId(
                batchedStore,
                producerUid,
                eventObs,
                targetOcc,
                5,
                10L,
                10L,
                () -> false);

        assertEquals(expectedTargetKey, resolved);
        assertTrue("Expected batch commit() before retry lookup", commitCalls.get() >= 1);
        assertEquals("Expected initial miss followed by committed hit", 2, selectCalls.get());
        assertEquals("Every selectEntries stream must be closed", selectCalls.get(), closedStreams.get());
    }

    @Test
    public void returnsNullPromptlyWhenStopped() {
        Occupancy targetOcc = buildOccupancy(42, 1793932457.0);
        IObsData eventObs = new ObsData.Builder()
                .withDataStream(BigId.fromLong(1, 1L))
                .withPhenomenonTime(Instant.parse("2026-11-06T02:34:17Z"))
                .withResult(Occupancy.fromOccupancy(targetOcc))
                .build();
        IObsStore unusedStore = (IObsStore) Proxy.newProxyInstance(
                IObsStore.class.getClassLoader(),
                new Class<?>[]{IObsStore.class},
                (proxy, method, args) -> {
                    throw new AssertionError("Should not query store when already stopped");
                });

        BigId resolved = EmlAutoAdjudicationHelper.resolvePersistedObsId(
                unusedStore,
                "urn:osh:sensor:rapiscan:lane-50",
                eventObs,
                targetOcc,
                10,
                100L,
                250L,
                () -> true);

        assertNull(resolved);
    }
}
