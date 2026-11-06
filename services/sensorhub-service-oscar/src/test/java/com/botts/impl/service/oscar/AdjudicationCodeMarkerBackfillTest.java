package com.botts.impl.service.oscar;

import com.botts.impl.system.lane.AdjudicationControl;
import com.botts.impl.system.lane.LaneSystem;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataComponent;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.command.CommandResult;
import org.sensorhub.api.command.CommandStatus;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.ObsData;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.datastore.h2.MVObsSystemDatabaseConfig;
import org.sensorhub.impl.datastore.h2.MVObsSystemDatabaseDescriptor;
import org.sensorhub.impl.sensor.SensorSystem;
import org.sensorhub.impl.system.wrapper.SystemWrapper;
import org.sensorhub.impl.utils.rad.model.Adjudication;
import org.sensorhub.impl.utils.rad.model.Occupancy;
import org.sensorhub.impl.utils.rad.model.OccupancyExtended;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.slf4j.LoggerFactory;
import org.vast.data.TextEncodingImpl;
import org.vast.sensorML.SMLHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.Assert.*;

public class AdjudicationCodeMarkerBackfillTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private SensorHub hub;
    private IObsSystemDatabase database;
    private LaneSystem lane;
    private AdjudicationControl control;

    private static class AccessibleControl extends AdjudicationControl {
        AccessibleControl(LaneSystem lane) {
            super(lane);
        }
    }

    @Before
    public void setUp() throws Exception {
        hub = new SensorHub();
        hub.start();

        var reg = hub.getModuleRegistry();
        var dbConfig = (MVObsSystemDatabaseConfig) reg.createModuleConfig(new MVObsSystemDatabaseDescriptor());
        dbConfig.id = "backfill-test-db";
        dbConfig.databaseNum = 9;
        dbConfig.storagePath = tempFolder.getRoot().toPath().resolve("backfill-test.dat").toString();
        var dbModule = reg.loadModule(dbConfig);
        dbModule.init();
        dbModule.start();
        database = (IObsSystemDatabase) dbModule;

        lane = new LaneSystem();
        lane.setParentHub(hub);
        control = new AccessibleControl(lane);
        control.setObsStore(database.getObservationStore());
    }

    @After
    public void tearDown() {
        if (hub != null)
            hub.stop();
    }

    @Test
    public void markerParsingAndOccupancyReplacementStayMutuallyExclusive() {
        assertEquals("code:4", Adjudication.adjudicationCodeMarker(4));
        assertEquals("code:9999", Adjudication.adjudicationCodeMarker(Adjudication.EML_NON_THREAT_CODE));
        assertEquals(OptionalInt.of(4), Adjudication.parseAdjudicationCodeMarker("code:4"));
        assertEquals(OptionalInt.of(9999), Adjudication.parseAdjudicationCodeMarker("  code:9999 "));
        assertEquals(OptionalInt.empty(), Adjudication.parseAdjudicationCodeMarker("eml-non-threat"));
        assertEquals(OptionalInt.empty(), Adjudication.parseAdjudicationCodeMarker("code:"));
        assertEquals(OptionalInt.empty(), Adjudication.parseAdjudicationCodeMarker("code:-1"));
        assertEquals(OptionalInt.empty(), Adjudication.parseAdjudicationCodeMarker("code:abc"));
        assertEquals(OptionalInt.empty(), Adjudication.parseAdjudicationCodeMarker(null));

        // Immutable List.of(...) passed to Builder must safely become mutable on update.
        Occupancy occupancy = new Occupancy.Builder()
                .samplingTime(1000.0)
                .occupancyCount(7)
                .startTime(1000.0)
                .endTime(1002.0)
                .gammaAlarm(true)
                .adjudicatedIds(List.of(Adjudication.EML_NON_THREAT_MARKER))
                .build();

        assertFalse(occupancy.hasAdjudicationCodeMarker());
        assertTrue(occupancy.recordAdjudicationCode(Adjudication.EML_NON_THREAT_CODE));
        assertFalse("Repeat recording of identical final marker must be a no-op",
                occupancy.recordAdjudicationCode(Adjudication.EML_NON_THREAT_CODE));
        assertEquals(List.of("eml-non-threat", "code:9999"), occupancy.getAdjudicatedIds());
        assertEquals(OptionalInt.of(9999), occupancy.getLatestAdjudicationCode());

        // Supervisor override replaces "code:9999" with "code:1" while preserving audit command IDs.
        occupancy.addAdjudicatedId("cmd-eml-1");
        occupancy.addAdjudicatedId("cmd-sup-2");
        assertTrue(occupancy.recordAdjudicationCode(1));
        assertEquals(List.of("eml-non-threat", "cmd-eml-1", "cmd-sup-2", "code:1"), occupancy.getAdjudicatedIds());
        assertEquals(OptionalInt.of(1), occupancy.getLatestAdjudicationCode());
    }

    @Test
    public void adjudicationControlRecordsLatestCodeMarkerAndExtractsSummary() throws Exception {
        @SuppressWarnings({"rawtypes", "unchecked"})
        DataComponent baseRecord = new OccupancyOutput(new SensorSystem()).getRecordDescription();
        BigId laneSysId = addSystem("urn:osh:system:lane1");
        BigId dsId = database.getObservationStore().getDataStreams().add(new DataStreamInfo.Builder()
                .withSystem(new FeatureId(laneSysId, "urn:osh:system:lane1"))
                .withName("occupancy")
                .withRecordDescription(baseRecord)
                .withRecordEncoding(new TextEncodingImpl())
                .build()).getInternalID();
        BigId cmdStreamId = database.getCommandStore().getCommandStreams().add(new CommandStreamInfo.Builder()
                .withSystem(new FeatureId(laneSysId, "urn:osh:system:lane1"))
                .withName(AdjudicationControl.NAME)
                .withRecordDescription(control.getCommandDescription())
                .withRecordEncoding(new TextEncodingImpl())
                .withResultDescription(control.getResultDescription())
                .withResultEncoding(new TextEncodingImpl())
                .build()).getInternalID();

        hub.getSystemDriverRegistry().registerDatabase("urn:osh:system:lane1", database);
        var laneConfig = lane.getConfiguration();
        if (laneConfig != null) {
            laneConfig.uniqueID = "urn:osh:system:lane1";
        }

        Occupancy initial = new Occupancy.Builder()
                .samplingTime(1_700_000_000.0)
                .occupancyCount(42)
                .startTime(1_700_000_000.0)
                .endTime(1_700_000_005.0)
                .gammaAlarm(true)
                .maxGammaCount(2500)
                .build();
        BigId obsKey = database.getObservationStore().add(new ObsData.Builder()
                .withDataStream(dsId)
                .withPhenomenonTime(Instant.ofEpochSecond(1_700_000_000L))
                .withResult(Occupancy.fromOccupancy(initial))
                .build());
        String encodedObsId = hub.getIdEncoders().getObsIdEncoder().encodeID(obsKey);

        // Submit initial adjudication (Code 6) with isotopes and filePaths, then re-adjudicate (Code 4).
        Adjudication firstAdj = new Adjudication.Builder()
                .feedback("Initial check")
                .adjudicationCode(6)
                .isotopes(List.of("Cesium137", "Cobalt60"))
                .secondaryInspectionStatus(Adjudication.SecondaryInspectionStatus.REQUESTED)
                .filePaths(List.of("/adjudication/photo1.jpg"))
                .occupancyObsId(encodedObsId)
                .vehicleId("TRK-10")
                .build();

        // Verify inline result summary extraction on complex variable-length arrays.
        DataComponent resStruct = control.getResultDescription().copy();
        var block = resStruct.createDataBlock();
        resStruct.setData(block);
        // Use real AdjudicationControl submission by mapping system UID via parentSystemUID fallback:
        LaneSystem mappedLane = new LaneSystem() {
            @Override
            public String getUniqueIdentifier() {
                return "urn:osh:system:lane1";
            }
        };
        mappedLane.setParentHub(hub);
        AccessibleControl mappedControl = new AccessibleControl(mappedLane);
        mappedControl.setObsStore(database.getObservationStore());

        BigId cmdId1 = BigId.fromLong(9, 101);
        ICommandStatus status1 = mappedControl.submitCommand(new CommandData.Builder()
                .withId(cmdId1)
                .withSender("operator1")
                .withCommandStream(cmdStreamId)
                .withParams(Adjudication.fromAdjudication(firstAdj))
                .build()).get();
        assertEquals(ICommandStatus.CommandStatusCode.COMPLETED, status1.getStatusCode());

        var summary1 = AdjudicationControl.extractCompletedResultSummary(status1);
        assertTrue(summary1.isPresent());
        assertEquals(encodedObsId, summary1.get().occupancyObsId());
        assertEquals(6, summary1.get().adjudicationCode());

        Occupancy afterFirst = Occupancy.toOccupancy(database.getObservationStore().get(obsKey).getResult());
        String encodedCmd1 = hub.getIdEncoders().getCommandIdEncoder().encodeID(cmdId1);
        assertEquals(List.of(encodedCmd1, "code:6"), afterFirst.getAdjudicatedIds());

        Adjudication secondAdj = new Adjudication.Builder()
                .feedback("Confirmed NORM")
                .adjudicationCode(4)
                .isotopes(List.of("Potassium"))
                .secondaryInspectionStatus(Adjudication.SecondaryInspectionStatus.COMPLETED)
                .filePaths(List.of())
                .occupancyObsId(encodedObsId)
                .vehicleId("TRK-10")
                .build();
        BigId cmdId2 = BigId.fromLong(9, 102);
        ICommandStatus status2 = mappedControl.submitCommand(new CommandData.Builder()
                .withId(cmdId2)
                .withSender("supervisor1")
                .withCommandStream(cmdStreamId)
                .withParams(Adjudication.fromAdjudication(secondAdj))
                .build()).get();
        assertEquals(ICommandStatus.CommandStatusCode.COMPLETED, status2.getStatusCode());

        Occupancy afterSecond = Occupancy.toOccupancy(database.getObservationStore().get(obsKey).getResult());
        String encodedCmd2 = hub.getIdEncoders().getCommandIdEncoder().encodeID(cmdId2);
        assertEquals(List.of(encodedCmd1, encodedCmd2, "code:4"), afterSecond.getAdjudicatedIds());
    }

    @Test
    public void backfillMigratesLegacyBaseAndExtendedOccupanciesIdempotentlyWithoutOverwritingLiveMarkers() throws Exception {
        @SuppressWarnings({"rawtypes", "unchecked"})
        DataComponent baseRecord = new OccupancyOutput(new SensorSystem()).getRecordDescription();
        DataComponent extendedRecord = OccupancyExtended.createExtendedRecordStructure();

        BigId rapiscanSysId = addSystem("urn:osh:system:rapiscan1");
        BigId rs350SysId = addSystem("urn:osh:system:rs350-1");
        BigId laneSysId = addSystem("urn:osh:system:lane-backfill");
        BigId baseDsId = database.getObservationStore().getDataStreams().add(new DataStreamInfo.Builder()
                .withSystem(new FeatureId(rapiscanSysId, "urn:osh:system:rapiscan1"))
                .withName("occupancy")
                .withRecordDescription(baseRecord)
                .withRecordEncoding(new TextEncodingImpl())
                .build()).getInternalID();
        BigId extDsId = database.getObservationStore().getDataStreams().add(new DataStreamInfo.Builder()
                .withSystem(new FeatureId(rs350SysId, "urn:osh:system:rs350-1"))
                .withName("occupancy")
                .withRecordDescription(extendedRecord)
                .withRecordEncoding(new TextEncodingImpl())
                .build()).getInternalID();

        BigId cmdStreamId = database.getCommandStore().getCommandStreams().add(new CommandStreamInfo.Builder()
                .withSystem(new FeatureId(laneSysId, "urn:osh:system:lane-backfill"))
                .withName(AdjudicationControl.NAME)
                .withRecordDescription(control.getCommandDescription())
                .withRecordEncoding(new TextEncodingImpl())
                .withResultDescription(control.getResultDescription())
                .withResultEncoding(new TextEncodingImpl())
                .build()).getInternalID();

        var idEncoder = hub.getIdEncoders().getObsIdEncoder();

        // 1. Legacy Rapiscan occupancy adjudicated twice before upgrade (no "code:*" marker yet).
        Occupancy legacyRapiscan = new Occupancy.Builder()
                .samplingTime(1_700_001_000.0)
                .occupancyCount(101)
                .startTime(1_700_001_000.0)
                .endTime(1_700_001_004.0)
                .gammaAlarm(true)
                .adjudicatedIds(List.of("legacy-cmd-1", "legacy-cmd-2"))
                .build();
        BigId rapiscanObsKey = database.getObservationStore().add(new ObsData.Builder()
                .withDataStream(baseDsId)
                .withPhenomenonTime(Instant.ofEpochSecond(1_700_001_000L))
                .withResult(Occupancy.fromOccupancy(legacyRapiscan))
                .build());
        String encodedRapiscanId = idEncoder.encodeID(rapiscanObsKey);

        // 2. Legacy RS350 OccupancyExtended adjudicated with custom code 105.
        OccupancyExtended legacyRs350 = (OccupancyExtended) new OccupancyExtended.Builder()
                .alarmCategory("Gamma-Neutron")
                .samplingTime(1_700_002_000.0)
                .occupancyCount(202)
                .startTime(1_700_002_000.0)
                .endTime(1_700_002_006.0)
                .gammaAlarm(true)
                .neutronAlarm(true)
                .adjudicatedIds(List.of("legacy-rs350-cmd"))
                .build();
        BigId rs350ObsKey = database.getObservationStore().add(new ObsData.Builder()
                .withDataStream(extDsId)
                .withPhenomenonTime(Instant.ofEpochSecond(1_700_002_000L))
                .withResult(OccupancyExtended.fromOccupancy(legacyRs350))
                .build());
        String encodedRs350Id = idEncoder.encodeID(rs350ObsKey);

        // 3. Legacy EML non-threat occupancy without a completed command status record.
        Occupancy legacyEml = new Occupancy.Builder()
                .samplingTime(1_700_002_500.0)
                .occupancyCount(250)
                .startTime(1_700_002_500.0)
                .endTime(1_700_002_503.0)
                .gammaAlarm(true)
                .adjudicatedIds(List.of(Adjudication.EML_NON_THREAT_MARKER))
                .build();
        BigId emlObsKey = database.getObservationStore().add(new ObsData.Builder()
                .withDataStream(baseDsId)
                .withPhenomenonTime(Instant.ofEpochSecond(1_700_002_500L))
                .withResult(Occupancy.fromOccupancy(legacyEml))
                .build());

        // 4. Occupancy that ALREADY received a live marker ("code:1") — backfill must NOT overwrite it!
        Occupancy alreadyMarked = new Occupancy.Builder()
                .samplingTime(1_700_003_000.0)
                .occupancyCount(303)
                .startTime(1_700_003_000.0)
                .endTime(1_700_003_003.0)
                .gammaAlarm(true)
                .adjudicatedIds(List.of("live-cmd", "code:1"))
                .build();
        BigId liveObsKey = database.getObservationStore().add(new ObsData.Builder()
                .withDataStream(baseDsId)
                .withPhenomenonTime(Instant.ofEpochSecond(1_700_003_000L))
                .withResult(Occupancy.fromOccupancy(alreadyMarked))
                .build());
        String encodedLiveId = idEncoder.encodeID(liveObsKey);

        // 5. Unadjudicated occupancy — must remain completely untouched even when datastore ignores CQL pushdown.
        Occupancy unadjudicated = new Occupancy.Builder()
                .samplingTime(1_700_003_500.0)
                .occupancyCount(404)
                .startTime(1_700_003_500.0)
                .endTime(1_700_003_502.0)
                .gammaAlarm(true)
                .build();
        BigId unadjudicatedObsKey = database.getObservationStore().add(new ObsData.Builder()
                .withDataStream(baseDsId)
                .withPhenomenonTime(Instant.ofEpochSecond(1_700_003_500L))
                .withResult(Occupancy.fromOccupancy(unadjudicated))
                .build());

        LaneSystem mappedLane = new LaneSystem() {
            @Override
            public String getUniqueIdentifier() {
                return "urn:osh:system:lane-backfill";
            }
        };
        mappedLane.setParentHub(hub);
        AccessibleControl helperControl = new AccessibleControl(mappedLane);
        helperControl.setObsStore(database.getObservationStore());

        recordPersistedCompletedStatus(cmdStreamId, helperControl, encodedRapiscanId, 6, Instant.parse("2026-11-01T10:00:00Z"));
        recordPersistedCompletedStatus(cmdStreamId, helperControl, encodedRapiscanId, 4, Instant.parse("2026-11-01T10:05:00Z"));
        recordPersistedCompletedStatus(cmdStreamId, helperControl, encodedRs350Id, 105, Instant.parse("2026-11-01T11:00:00Z"));
        recordPersistedCompletedStatus(cmdStreamId, helperControl, encodedLiveId, 8, Instant.parse("2026-11-01T09:00:00Z"));

        Path marker = tempFolder.getRoot().toPath().resolve(".adjudication-code-markers-v1.done");
        var backfill = new AdjudicationCodeMarkerBackfill(
                database,
                idEncoder,
                marker,
                LoggerFactory.getLogger(AdjudicationCodeMarkerBackfillTest.class));

        int updated = backfill.runSynchronouslyForTest();
        assertEquals(3, updated);
        assertTrue("Completion marker file must exist after clean backfill", Files.isRegularFile(marker));

        Occupancy migratedRapiscan = Occupancy.toOccupancy(database.getObservationStore().get(rapiscanObsKey).getResult());
        assertEquals(List.of("legacy-cmd-1", "legacy-cmd-2", "code:4"), migratedRapiscan.getAdjudicatedIds());

        OccupancyExtended migratedRs350 = OccupancyExtended.toOccupancy(database.getObservationStore().get(rs350ObsKey).getResult());
        assertEquals(List.of("legacy-rs350-cmd", "code:105"), migratedRs350.getAdjudicatedIds());
        assertEquals("Gamma-Neutron", migratedRs350.getAlarmCategory());

        Occupancy migratedEml = Occupancy.toOccupancy(database.getObservationStore().get(emlObsKey).getResult());
        assertEquals(List.of("eml-non-threat", "code:9999"), migratedEml.getAdjudicatedIds());

        Occupancy preservedLive = Occupancy.toOccupancy(database.getObservationStore().get(liveObsKey).getResult());
        assertEquals("Live marker must never be overwritten by older historical status",
                List.of("live-cmd", "code:1"), preservedLive.getAdjudicatedIds());

        Occupancy untouchedUnadjudicated = Occupancy.toOccupancy(database.getObservationStore().get(unadjudicatedObsKey).getResult());
        assertTrue(untouchedUnadjudicated.getAdjudicatedIds().isEmpty());

        // Verify all EventFilter CQL2 adjudicationCode predicates parse cleanly in GeoTools CQL2.
        assertNotNull(ObsFilter.parseCqlFilter("adjudicatedIdsCount>0 AND adjudicatedIds='code:4'"));
        assertNotNull(ObsFilter.parseCqlFilter("adjudicatedIdsCount>0 AND ((adjudicatedIds='code:4') OR (adjudicatedIds='code:9999'))"));
        assertNotNull(ObsFilter.parseCqlFilter("(adjudicatedIdsCount=0 OR adjudicatedIds!='code:9999')"));
        assertNotNull(ObsFilter.parseCqlFilter("adjudicatedIdsCount=0"));
        assertNotNull(ObsFilter.parseCqlFilter("adjudicatedIdsCount>0"));

        // Subsequent startup with completion marker present performs zero work.
        assertEquals(0, backfill.runSynchronouslyForTest());
    }

    private BigId addSystem(String uid) throws DataStoreException {
        AbstractProcess process = new SMLHelper().createPhysicalComponent().uniqueID(uid).name(uid).build();
        return database.getSystemDescStore().add(new SystemWrapper(process)).getInternalID();
    }

    private void recordPersistedCompletedStatus(
            BigId cmdStreamId,
            AccessibleControl helperControl,
            String encodedObsId,
            int code,
            Instant reportTime) throws Exception {
        Adjudication adj = new Adjudication.Builder()
                .feedback("Backfill fixture")
                .adjudicationCode(code)
                .isotopes(List.of("Uranium238"))
                .secondaryInspectionStatus(Adjudication.SecondaryInspectionStatus.NONE)
                .filePaths(List.of("/adjudication/spec.n42"))
                .occupancyObsId(encodedObsId)
                .vehicleId("VEH-1")
                .build();

        BigId cmdKey = database.getCommandStore().add(new CommandData.Builder()
                .withCommandStream(cmdStreamId)
                .withIssueTime(reportTime)
                .withSender("admin")
                .withParams(Adjudication.fromAdjudication(adj))
                .build());

        var method = AdjudicationControl.class.getDeclaredMethod("createResultData", Adjudication.class, String.class);
        method.setAccessible(true);
        var resultBlock = (net.opengis.swe.v20.DataBlock) method.invoke(helperControl, adj, "admin");

        database.getCommandStatusStore().add(new CommandStatus.Builder()
                .withCommand(cmdKey)
                .withReportTime(reportTime)
                .withStatusCode(ICommandStatus.CommandStatusCode.COMPLETED)
                .withResult(CommandResult.withData(resultBlock))
                .build());
    }
}
