package com.botts.impl.system.lane.helpers.webid;

import com.botts.impl.system.lane.WebIdOutput;
import net.opengis.swe.v20.DataComponent;
import org.junit.Test;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsData;
import org.sensorhub.api.data.ObsEvent;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.model.Occupancy;
import org.sensorhub.impl.utils.rad.model.WebIdAnalysis;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.vast.data.TextEncodingImpl;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WebIdHelperStreamCloseTest {

    private static DataComponent createOccupancyRecordStructure() {
        RADHelper radHelper = new RADHelper();
        return radHelper.createRecord()
                .name(OccupancyOutput.NAME)
                .updatable(true)
                .addField("samplingTime", radHelper.createPrecisionTimeStamp())
                .addField("occupancyCount", radHelper.createOccupancyCount())
                .addField("occupancyStart", radHelper.createOccupancyStartTime())
                .addField("occupancyEnd", radHelper.createOccupancyEndTime())
                .addField("neutronBackground", radHelper.createNeutronBackground())
                .addField("gammaAlarm", radHelper.createGammaAlarm())
                .addField("neutronAlarm", radHelper.createNeutronAlarm())
                .addField("maxGamma", radHelper.createMaxGamma())
                .addField("maxNeutron", radHelper.createMaxNeutron())
                .addField("adjudicatedIdsCount", radHelper.createAdjudicatedIdCount())
                .addField("adjudicatedIds", radHelper.createAdjudicatedIdsArray())
                .addField("videoPathsCount", radHelper.createVideoPathCount())
                .addField("videoPaths", radHelper.createVideoPathsArray())
                .addField("webIdObsIdsCount", radHelper.createWebIdObsIdCount())
                .addField("webIdObsIds", radHelper.createWebIdObsIdsArray())
                .build();
    }

    private static IdEncoder createSimpleIdEncoder() {
        return new IdEncoder() {
            @Override
            public String encodeID(BigId internalID) {
                return "obs-" + internalID.getIdAsLong();
            }

            @Override
            public BigId decodeID(String encodedID) {
                return BigId.fromLong(0, Long.parseLong(encodedID.replace("obs-", "")));
            }
        };
    }

    @Test
    public void selectKeysUsesLimitOneAndClosesStreamsOnMissAndRepeatedCorrelations() {
        Instant sampleInstant = Instant.parse("2026-11-24T12:00:10Z");
        double sampleEpochSec = sampleInstant.getEpochSecond();
        BigId occDsId = BigId.fromLong(0, 10L);
        BigId webIdDsId = BigId.fromLong(0, 20L);

        DataComponent occStruct = createOccupancyRecordStructure();
        IDataStreamInfo occDsInfo = new DataStreamInfo.Builder()
                .withSystem(new FeatureId(BigId.fromLong(0, 1L), "urn:osh:sensor:rpm1"))
                .withName("Occupancy")
                .withRecordDescription(occStruct)
                .withRecordEncoding(new TextEncodingImpl())
                .build();

        IDataStreamStore dsStore = (IDataStreamStore) Proxy.newProxyInstance(
                WebIdHelperStreamCloseTest.class.getClassLoader(),
                new Class<?>[]{IDataStreamStore.class},
                (proxy, method, args) -> {
                    if ("get".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof DataStreamKey key
                            && occDsId.equals(key.getInternalID())) {
                        return occDsInfo;
                    }
                    return null;
                });

        AtomicInteger webIdOpened = new AtomicInteger();
        AtomicInteger webIdClosed = new AtomicInteger();
        AtomicInteger occOpened = new AtomicInteger();
        AtomicInteger occClosed = new AtomicInteger();
        AtomicReference<ObsFilter> lastWebIdFilter = new AtomicReference<>();
        AtomicReference<ObsFilter> lastOccFilter = new AtomicReference<>();
        List<BigId> updatedWebIdKeys = new ArrayList<>();
        List<BigId> updatedOccKeys = new ArrayList<>();

        AtomicReference<BigId> webIdMatchKey = new AtomicReference<>(null);
        AtomicReference<BigId> occMatchKey = new AtomicReference<>(null);

        IObsStore webIdStore = (IObsStore) Proxy.newProxyInstance(
                WebIdHelperStreamCloseTest.class.getClassLoader(),
                new Class<?>[]{IObsStore.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "selectKeys" -> {
                            lastWebIdFilter.set((ObsFilter) args[0]);
                            webIdOpened.incrementAndGet();
                            BigId key = webIdMatchKey.get();
                            Stream<BigId> base = key == null ? Stream.empty() : Stream.of(key, BigId.fromLong(0, 999L));
                            return base.onClose(webIdClosed::incrementAndGet);
                        }
                        case "put" -> {
                            updatedWebIdKeys.add((BigId) args[0]);
                            return (IObsData) args[1];
                        }
                        case "getDataStreams" -> {
                            return dsStore;
                        }
                        default -> {
                            return null;
                        }
                    }
                });

        IObsStore occStore = (IObsStore) Proxy.newProxyInstance(
                WebIdHelperStreamCloseTest.class.getClassLoader(),
                new Class<?>[]{IObsStore.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "selectKeys" -> {
                            lastOccFilter.set((ObsFilter) args[0]);
                            occOpened.incrementAndGet();
                            BigId key = occMatchKey.get();
                            Stream<BigId> base = key == null ? Stream.empty() : Stream.of(key, BigId.fromLong(0, 888L));
                            return base.onClose(occClosed::incrementAndGet);
                        }
                        case "put" -> {
                            updatedOccKeys.add((BigId) args[0]);
                            return (IObsData) args[1];
                        }
                        case "getDataStreams" -> {
                            return dsStore;
                        }
                        default -> {
                            return null;
                        }
                    }
                });

        WebIdHelper helper = new WebIdHelper(
                "urn:osh:system:lane1",
                "urn:osh:sensor:rpm1",
                webIdStore,
                occStore,
                createSimpleIdEncoder(),
                0L);

        WebIdAnalysis analysis = new WebIdAnalysis.Builder()
                .sampleTime(sampleInstant)
                .isotopes(List.of())
                .isotopeString("Cs-137")
                .drf("1x1-NaI")
                .estimatedDose(0.12)
                .chi2(1.05)
                .errorMessage("")
                .analysisWarnings(List.of())
                .occupancyObsId("")
                .build();
        ObsData webIdObs = new ObsData.Builder()
                .withDataStream(webIdDsId)
                .withPhenomenonTime(sampleInstant)
                .withResult(WebIdAnalysis.fromWebIdAnalysis(analysis))
                .build();

        Occupancy occupancy = new Occupancy.Builder()
                .samplingTime(sampleEpochSec)
                .occupancyCount(1)
                .startTime(sampleEpochSec - 5.0)
                .endTime(sampleEpochSec)
                .neutronBackground(12.0)
                .gammaAlarm(true)
                .neutronAlarm(false)
                .maxGammaCount(540)
                .maxNeutronCount(2)
                .adjudicatedIds(List.of())
                .videoPaths(List.of())
                .webIdObsIds(List.of())
                .build();
        ObsData occObs = new ObsData.Builder()
                .withDataStream(occDsId)
                .withPhenomenonTime(sampleInstant)
                .withResult(Occupancy.fromOccupancy(occupancy))
                .build();

        // 1. Empty lookup miss still enforces limit=1 and closes both streams
        helper.processWebIdEvent(new ObsEvent(System.currentTimeMillis(), "urn:osh:system:lane1", WebIdOutput.NAME, webIdObs));
        helper.processOccupancyEvent(new ObsEvent(System.currentTimeMillis(), "urn:osh:sensor:rpm1", OccupancyOutput.NAME, occObs));

        assertNotNull(lastWebIdFilter.get());
        assertNotNull(lastOccFilter.get());
        assertEquals(1L, lastWebIdFilter.get().getLimit());
        assertEquals(1L, lastOccFilter.get().getLimit());
        assertEquals(1, webIdOpened.get());
        assertEquals(1, webIdClosed.get());
        assertEquals(1, occOpened.get());
        assertEquals(1, occClosed.get());
        assertEquals(0, updatedWebIdKeys.size());
        assertEquals(0, updatedOccKeys.size());

        // 2. Three consecutive correlations (6 lookups > 2-session DuckDB pool) close every stream cleanly
        for (int round = 1; round <= 3; round++) {
            BigId wKey = BigId.fromLong(0, 100L + round);
            BigId oKey = BigId.fromLong(0, 200L + round);
            webIdMatchKey.set(wKey);
            occMatchKey.set(oKey);

            ObsData roundWebIdObs = new ObsData.Builder()
                    .withDataStream(webIdDsId)
                    .withPhenomenonTime(sampleInstant)
                    .withResult(WebIdAnalysis.fromWebIdAnalysis(analysis))
                    .build();
            ObsData roundOccObs = new ObsData.Builder()
                    .withDataStream(occDsId)
                    .withPhenomenonTime(sampleInstant)
                    .withResult(Occupancy.fromOccupancy(occupancy))
                    .build();

            helper.processWebIdEvent(new ObsEvent(System.currentTimeMillis(), "urn:osh:system:lane1", WebIdOutput.NAME, roundWebIdObs));
            helper.processOccupancyEvent(new ObsEvent(System.currentTimeMillis(), "urn:osh:sensor:rpm1", OccupancyOutput.NAME, roundOccObs));
        }

        assertEquals(4, webIdOpened.get());
        assertEquals("Every WebId selectKeys stream must be closed", 4, webIdClosed.get());
        assertEquals(4, occOpened.get());
        assertEquals("Every Occupancy selectKeys stream must be closed", 4, occClosed.get());
        assertEquals(3, updatedWebIdKeys.size());
        assertEquals(3, updatedOccKeys.size());
    }
}
