/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package org.sensorhub.impl.utils.rad.model;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.impl.utils.rad.RADHelper;

import java.time.Instant;

/**
 * Automated container/vehicle ID candidate read from lane camera footage during an occupancy.
 *
 * <p>Published on a lane's optional {@code vehicleOcr} output and correlated to the originating
 * occupancy via {@link #getOccupancyObsId()}.</p>
 */
public class VehicleOcrResult {

    public static final String ID_TYPE_CONTAINER = "container";
    public static final String ID_TYPE_PLATE = "plate";

    private Instant sampleTime;
    private String occupancyObsId = "";
    private String idType = ID_TYPE_CONTAINER;
    private String value = "";
    private String normalizedValue = "";
    private boolean checksumValid;
    private float confidence;
    private int readCount;
    private String cameraUid = "";
    private Instant frameTime;
    private String evidenceImagePath = "";

    public VehicleOcrResult() {
    }

    public Instant getSampleTime() { return sampleTime; }
    public String getOccupancyObsId() { return occupancyObsId; }
    public String getIdType() { return idType; }
    public String getValue() { return value; }
    public String getNormalizedValue() { return normalizedValue; }
    public boolean isChecksumValid() { return checksumValid; }
    public float getConfidence() { return confidence; }
    public int getReadCount() { return readCount; }
    public String getCameraUid() { return cameraUid; }
    public Instant getFrameTime() { return frameTime; }
    public String getEvidenceImagePath() { return evidenceImagePath; }

    public void setOccupancyObsId(String occupancyObsId) {
        this.occupancyObsId = emptyIfNull(occupancyObsId);
    }

    @Override
    public String toString() {
        return "VehicleOcrResult{" + idType + "=" + normalizedValue
                + ", checksumValid=" + checksumValid
                + ", confidence=" + confidence
                + ", readCount=" + readCount + "}";
    }

    public static class Builder {
        private final VehicleOcrResult instance = new VehicleOcrResult();

        public Builder sampleTime(Instant sampleTime) {
            this.instance.sampleTime = sampleTime;
            return this;
        }

        public Builder occupancyObsId(String occupancyObsId) {
            this.instance.occupancyObsId = emptyIfNull(occupancyObsId);
            return this;
        }

        public Builder idType(String idType) {
            this.instance.idType = emptyIfNull(idType);
            return this;
        }

        public Builder value(String value) {
            this.instance.value = emptyIfNull(value);
            return this;
        }

        public Builder normalizedValue(String normalizedValue) {
            this.instance.normalizedValue = emptyIfNull(normalizedValue);
            return this;
        }

        public Builder checksumValid(boolean checksumValid) {
            this.instance.checksumValid = checksumValid;
            return this;
        }

        public Builder confidence(float confidence) {
            this.instance.confidence = Float.isFinite(confidence)
                    ? Math.max(0f, Math.min(1f, confidence))
                    : 0f;
            return this;
        }

        public Builder readCount(int readCount) {
            this.instance.readCount = Math.max(0, readCount);
            return this;
        }

        public Builder cameraUid(String cameraUid) {
            this.instance.cameraUid = emptyIfNull(cameraUid);
            return this;
        }

        public Builder frameTime(Instant frameTime) {
            this.instance.frameTime = frameTime;
            return this;
        }

        public Builder evidenceImagePath(String evidenceImagePath) {
            this.instance.evidenceImagePath = emptyIfNull(evidenceImagePath);
            return this;
        }

        public VehicleOcrResult build() {
            return this.instance;
        }
    }

    public static DataBlock fromVehicleOcrResult(VehicleOcrResult result) {
        DataComponent recordStructure = new RADHelper().createVehicleOcrRecord();
        return createDataBlock(recordStructure, result);
    }

    public static DataBlock createDataBlock(DataComponent recordStructure, VehicleOcrResult result) {
        DataBlock dataBlock = recordStructure.createDataBlock();
        Instant sampleTs = result.getSampleTime() != null ? result.getSampleTime() : Instant.now();
        Instant frameTs = result.getFrameTime() != null ? result.getFrameTime() : sampleTs;

        int index = 0;
        dataBlock.setTimeStamp(index++, sampleTs);
        dataBlock.setStringValue(index++, emptyIfNull(result.getOccupancyObsId()));
        dataBlock.setStringValue(index++, emptyIfNull(result.getIdType()));
        dataBlock.setStringValue(index++, emptyIfNull(result.getValue()));
        dataBlock.setStringValue(index++, emptyIfNull(result.getNormalizedValue()));
        dataBlock.setBooleanValue(index++, result.isChecksumValid());
        dataBlock.setFloatValue(index++, result.getConfidence());
        dataBlock.setIntValue(index++, result.getReadCount());
        dataBlock.setStringValue(index++, emptyIfNull(result.getCameraUid()));
        dataBlock.setTimeStamp(index++, frameTs);
        dataBlock.setStringValue(index, emptyIfNull(result.getEvidenceImagePath()));
        return dataBlock;
    }

    public static VehicleOcrResult toVehicleOcrResult(DataBlock dataBlock) {
        int index = 0;
        var sampleTime = dataBlock.getTimeStamp(index++);
        var occupancyObsId = dataBlock.getStringValue(index++);
        var idType = dataBlock.getStringValue(index++);
        var value = dataBlock.getStringValue(index++);
        var normalizedValue = dataBlock.getStringValue(index++);
        var checksumValid = dataBlock.getBooleanValue(index++);
        var confidence = dataBlock.getFloatValue(index++);
        var readCount = dataBlock.getIntValue(index++);
        var cameraUid = dataBlock.getStringValue(index++);
        var frameTime = dataBlock.getTimeStamp(index++);
        var evidenceImagePath = dataBlock.getStringValue(index);

        return new Builder()
                .sampleTime(sampleTime)
                .occupancyObsId(occupancyObsId)
                .idType(idType)
                .value(value)
                .normalizedValue(normalizedValue)
                .checksumValid(checksumValid)
                .confidence(confidence)
                .readCount(readCount)
                .cameraUid(cameraUid)
                .frameTime(frameTime)
                .evidenceImagePath(evidenceImagePath)
                .build();
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s;
    }
}
