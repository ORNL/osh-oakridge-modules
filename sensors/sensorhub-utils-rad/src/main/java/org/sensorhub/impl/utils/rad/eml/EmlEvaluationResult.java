package org.sensorhub.impl.utils.rad.eml;

import org.sensorhub.impl.utils.rad.model.Adjudication;

import java.util.List;
import java.util.Locale;

public record EmlEvaluationResult(
        String versionId,
        String modelId,
        double primaryThreshold,
        String portId,
        long laneId,
        long dateTimeEpochMillis,
        long segmentId,
        String rpmResult,
        boolean rpmGammaAlert,
        boolean rpmNeutronAlert,
        boolean rpmScanError,
        String result,
        boolean fallback,
        String exceptionMessage,
        double investigateProbability,
        double releaseProbability,
        boolean ernieGammaAlert,
        boolean ernieNeutronAlert,
        int vehicleClass,
        double vehicleLength,
        String yellowLightMessage,
        List<EmlSourceBreakdown> sources,
        EmlSourceBreakdown overallSource
) {
    public EmlEvaluationResult {
        versionId = versionId == null ? "" : versionId;
        modelId = modelId == null ? "" : modelId;
        portId = portId == null ? "" : portId;
        rpmResult = (rpmResult == null || rpmResult.isBlank()) ? "NONE" : rpmResult;
        result = (result == null || result.isBlank()) ? "NONE" : result;
        yellowLightMessage = yellowLightMessage == null ? "" : yellowLightMessage;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public boolean isAutoReleaseNonThreat() {
        return "RELEASE".equalsIgnoreCase(result)
                && !fallback
                && (exceptionMessage == null || exceptionMessage.isBlank());
    }

    public String toAdjudicationFeedback() {
        String resolvedSourceType = overallSource != null ? overallSource.sourceType() : "NonEmitting";
        double probNorm = overallSource != null ? overallSource.probabilityNorm() : 0.0;
        double probNonEmit = overallSource != null ? overallSource.probabilityNonEmitting() : releaseProbability;
        double probThreat = overallSource != null ? overallSource.probabilityThreat() : investigateProbability;

        String summary = String.format(
                Locale.ROOT,
                "Automated EML Non-Threat release (Release: %.1f%%, Investigate: %.1f%%, Threshold: %.1f%%, Model: %s v%s)",
                releaseProbability * 100.0,
                investigateProbability * 100.0,
                primaryThreshold * 100.0,
                modelId.isEmpty() ? "EML" : modelId,
                versionId.isEmpty() ? "unknown" : versionId);

        StringBuilder json = new StringBuilder(384);
        json.append('{')
                .append("\"disposition\":\"EML Non-Threat\",")
                .append("\"result\":\"").append(escapeJson(result)).append("\",")
                .append("\"releaseProbability\":").append(formatDouble(releaseProbability)).append(',')
                .append("\"investigateProbability\":").append(formatDouble(investigateProbability)).append(',')
                .append("\"primaryThreshold\":").append(formatDouble(primaryThreshold)).append(',')
                .append("\"modelId\":\"").append(escapeJson(modelId)).append("\",")
                .append("\"versionId\":\"").append(escapeJson(versionId)).append("\",")
                .append("\"vehicleClass\":").append(vehicleClass).append(',')
                .append("\"vehicleLength\":").append(formatDouble(vehicleLength)).append(',')
                .append("\"ernieGammaAlert\":").append(ernieGammaAlert).append(',')
                .append("\"ernieNeutronAlert\":").append(ernieNeutronAlert).append(',')
                .append("\"rpmGammaAlert\":").append(rpmGammaAlert).append(',')
                .append("\"rpmNeutronAlert\":").append(rpmNeutronAlert).append(',')
                .append("\"rpmScanError\":").append(rpmScanError).append(',')
                .append("\"overallSourceType\":\"").append(escapeJson(resolvedSourceType)).append("\",")
                .append("\"probabilityNORM\":").append(formatDouble(probNorm)).append(',')
                .append("\"probabilityNonEmitting\":").append(formatDouble(probNonEmit)).append(',')
                .append("\"probabilityThreat\":").append(formatDouble(probThreat))
                .append('}');

        return summary + "\n" + Adjudication.EML_METADATA_PREFIX + json;
    }

    private static String formatDouble(double value) {
        if (!Double.isFinite(value))
            return "0.0";
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String escapeJson(String input) {
        if (input == null || input.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20)
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else
                        sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
