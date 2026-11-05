package org.sensorhub.impl.utils.rad.eml;

import java.time.Instant;

public record EmlRuntimeStatus(
        boolean loaded,
        String fileName,
        String sha256,
        String versionId,
        String collimatedModelId,
        String uncollimatedModelId,
        Instant loadedAt,
        String lastError
) {
    public static EmlRuntimeStatus notLoaded(String lastError) {
        return new EmlRuntimeStatus(false, "", "", "", "", "", null, lastError == null ? "" : lastError);
    }

    public String toSummaryText() {
        if (!loaded) {
            if (lastError != null && !lastError.isBlank())
                return "Not loaded (" + lastError + ")";
            return "Not loaded";
        }
        String ver = (versionId == null || versionId.isBlank()) ? "loaded" : ("v" + versionId);
        String model = (collimatedModelId == null || collimatedModelId.isBlank()) ? "" : (", " + collimatedModelId);
        return "Active: " + fileName + " (" + ver + model + ")";
    }
}
