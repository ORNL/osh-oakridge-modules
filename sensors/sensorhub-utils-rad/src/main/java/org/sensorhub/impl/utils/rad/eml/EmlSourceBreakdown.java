package org.sensorhub.impl.utils.rad.eml;

public record EmlSourceBreakdown(
        String sourceType,
        String classifierUsed,
        double xLocation1,
        double xLocation2,
        double yLocation,
        double zLocation,
        double probabilityNonEmitting,
        double probabilityNorm,
        double probabilityThreat
) {
    public EmlSourceBreakdown {
        sourceType = (sourceType == null || sourceType.isBlank()) ? "NONE" : sourceType;
        classifierUsed = classifierUsed == null ? "" : classifierUsed;
    }
}
