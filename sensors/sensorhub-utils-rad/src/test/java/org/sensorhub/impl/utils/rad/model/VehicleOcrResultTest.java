/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package org.sensorhub.impl.utils.rad.model;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataRecord;
import org.junit.Test;
import org.sensorhub.impl.utils.rad.RADHelper;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VehicleOcrResultTest {

    @Test
    public void roundTripsAllElevenSweFields() {
        Instant sampleTime = Instant.parse("2026-11-14T05:00:00.123Z");
        Instant frameTime = Instant.parse("2026-11-14T04:59:58.500Z");

        VehicleOcrResult expected = new VehicleOcrResult.Builder()
                .sampleTime(sampleTime)
                .occupancyObsId("obs-uuid-1234")
                .idType(VehicleOcrResult.ID_TYPE_CONTAINER)
                .value("CSQU 305438 3")
                .normalizedValue("CSQU3054383")
                .checksumValid(true)
                .confidence(0.94f)
                .readCount(4)
                .cameraUid("urn:osh:sensor:ffmpeg:lane-01-cam-0")
                .frameTime(frameTime)
                .evidenceImagePath("ocr/crops/2026-11-14/crop-1.jpg")
                .build();

        DataRecord record = new RADHelper().createVehicleOcrRecord();
        assertEquals(11, record.getNumFields());
        assertEquals(RADHelper.getRadUri("OCRValue"), record.getField("value").getDefinition());
        assertEquals(RADHelper.getRadUri("VehicleID"), record.getField("normalizedValue").getDefinition());

        DataBlock block = VehicleOcrResult.fromVehicleOcrResult(expected);
        VehicleOcrResult decoded = VehicleOcrResult.toVehicleOcrResult(block);

        assertEquals(sampleTime, decoded.getSampleTime());
        assertEquals("obs-uuid-1234", decoded.getOccupancyObsId());
        assertEquals(VehicleOcrResult.ID_TYPE_CONTAINER, decoded.getIdType());
        assertEquals("CSQU 305438 3", decoded.getValue());
        assertEquals("CSQU3054383", decoded.getNormalizedValue());
        assertTrue(decoded.isChecksumValid());
        assertEquals(0.94f, decoded.getConfidence(), 0.0001f);
        assertEquals(4, decoded.getReadCount());
        assertEquals("urn:osh:sensor:ffmpeg:lane-01-cam-0", decoded.getCameraUid());
        assertEquals(frameTime, decoded.getFrameTime());
        assertEquals("ocr/crops/2026-11-14/crop-1.jpg", decoded.getEvidenceImagePath());
    }
}
