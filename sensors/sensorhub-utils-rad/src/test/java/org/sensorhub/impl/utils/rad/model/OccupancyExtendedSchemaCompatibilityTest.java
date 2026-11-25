package org.sensorhub.impl.utils.rad.model;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataRecord;
import org.junit.Test;
import org.sensorhub.impl.sensor.SensorSystem;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies backwards-compatible serialization and deserialization across
 * 16-field base {@link Occupancy}, 17-field legacy v1 {@link OccupancyExtended},
 * and 19-field v2 {@link OccupancyExtended} schemas (including re-adjudication
 * writeback with populated dynamic arrays).
 */
public class OccupancyExtendedSchemaCompatibilityTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void roundTripsBaseLegacyV1AndV2SchemasWithoutAtomCountCorruption() {
        DataRecord baseSchema = (DataRecord) new OccupancyOutput(new SensorSystem()).getRecordDescription();
        DataRecord v1Schema = OccupancyExtended.createLegacyV1ExtendedRecordStructure();
        DataRecord v2Schema = OccupancyExtended.createExtendedRecordStructure();

        assertFalse(OccupancyExtended.isExtendedSchema(baseSchema));
        assertFalse(OccupancyExtended.hasLocationFields(baseSchema));

        assertTrue(OccupancyExtended.isExtendedSchema(v1Schema));
        assertFalse(OccupancyExtended.hasLocationFields(v1Schema));

        assertTrue(OccupancyExtended.isExtendedSchema(v2Schema));
        assertTrue(OccupancyExtended.hasLocationFields(v2Schema));

        // 1. Legacy v1 (17-field) empty-array datablock has 13 scalar/count atoms (12 base + 1 alarmCategoryCode)
        OccupancyExtended v1Initial = (OccupancyExtended) new OccupancyExtended.Builder()
                .alarmCategory("Gamma-Neutron")
                .samplingTime(1700000100.0)
                .occupancyCount(42)
                .startTime(1700000095.0)
                .endTime(1700000100.0)
                .neutronBackground(12.5)
                .gammaAlarm(true)
                .neutronAlarm(true)
                .maxGammaCount(4200)
                .maxNeutronCount(85)
                .adjudicatedIds(new ArrayList<>(List.of("cmd-1")))
                .videoPaths(new ArrayList<>(List.of("videos/clip1.mp4", "videos/clip2.mp4")))
                .webIdObsIds(new ArrayList<>(List.of("webid-1")))
                .build();

        DataBlock v1Block = OccupancyExtended.fromOccupancy(v1Initial, false);
        int expectedV1Atoms = v1Schema.createDataBlock().getAtomCount() + 1 + 2 + 1;
        assertEquals(expectedV1Atoms, v1Block.getAtomCount());

        // Reading v1 datablock via readOccupancy(v1Schema, ...) and generic toOccupancy(...)
        Occupancy readFromV1 = OccupancyExtended.readOccupancy(v1Schema, v1Block);
        assertTrue(readFromV1 instanceof OccupancyExtended);
        OccupancyExtended readExtV1 = (OccupancyExtended) readFromV1;
        assertEquals("Gamma-Neutron", readExtV1.getAlarmCategory());
        assertFalse(readExtV1.hasLocation());
        assertEquals(0.0, readExtV1.getLatitude(), 1e-9);
        assertEquals(0.0, readExtV1.getLongitude(), 1e-9);
        assertEquals(List.of("cmd-1"), readExtV1.getAdjudicatedIds());
        assertEquals(List.of("videos/clip1.mp4", "videos/clip2.mp4"), readExtV1.getVideoPaths());
        assertEquals(List.of("webid-1"), readExtV1.getWebIdObsIds());

        // Mutating (e.g. re-adjudication) and writing back against v1 schema MUST preserve v1 atom layout
        readExtV1.addAdjudicatedId("cmd-2");
        DataBlock rewrittenV1 = OccupancyExtended.writeOccupancy(v1Schema, readExtV1);
        assertEquals(expectedV1Atoms + 1, rewrittenV1.getAtomCount());
        OccupancyExtended rereadV1 = (OccupancyExtended) OccupancyExtended.readOccupancy(v1Schema, rewrittenV1);
        assertEquals(List.of("cmd-1", "cmd-2"), rereadV1.getAdjudicatedIds());
        assertEquals("Gamma-Neutron", rereadV1.getAlarmCategory());
        assertFalse(rereadV1.hasLocation());

        // 2. Current v2 (19-field) datablock with mobile GPS coordinates
        OccupancyExtended v2Mobile = (OccupancyExtended) new OccupancyExtended.Builder()
                .alarmCategory("Gamma")
                .location(35.9312, -84.3104)
                .samplingTime(1700000200.0)
                .occupancyCount(7)
                .startTime(1700000190.0)
                .endTime(1700000200.0)
                .neutronBackground(3.0)
                .gammaAlarm(true)
                .neutronAlarm(false)
                .maxGammaCount(1850)
                .maxNeutronCount(4)
                .adjudicatedIds(new ArrayList<>())
                .videoPaths(new ArrayList<>())
                .webIdObsIds(new ArrayList<>(List.of("webid-mobile-7")))
                .build();

        DataBlock v2Block = OccupancyExtended.writeOccupancy(v2Schema, v2Mobile);
        int expectedV2Atoms = v2Schema.createDataBlock().getAtomCount() + 1;
        assertEquals(expectedV2Atoms, v2Block.getAtomCount());

        OccupancyExtended readExtV2 = (OccupancyExtended) OccupancyExtended.readOccupancy(v2Schema, v2Block);
        assertTrue(readExtV2.hasLocation());
        assertEquals(35.9312, readExtV2.getLatitude(), 1e-6);
        assertEquals(-84.3104, readExtV2.getLongitude(), 1e-6);
        assertEquals("Gamma", readExtV2.getAlarmCategory());
        assertEquals(List.of("webid-mobile-7"), readExtV2.getWebIdObsIds());

        // 3. Base 16-field Occupancy schema remains untouched
        Occupancy baseOcc = new Occupancy.Builder()
                .samplingTime(1700000300.0)
                .occupancyCount(99)
                .startTime(1700000296.0)
                .endTime(1700000300.0)
                .gammaAlarm(false)
                .neutronAlarm(false)
                .maxGammaCount(400)
                .maxNeutronCount(10)
                .build();
        DataBlock baseBlock = OccupancyExtended.writeOccupancy(baseSchema, baseOcc);
        assertEquals(baseSchema.createDataBlock().getAtomCount(), baseBlock.getAtomCount());
        Occupancy rereadBase = OccupancyExtended.readOccupancy(baseSchema, baseBlock);
        assertFalse(rereadBase instanceof OccupancyExtended);
        assertEquals(99, rereadBase.getOccupancyCount());
    }
}
