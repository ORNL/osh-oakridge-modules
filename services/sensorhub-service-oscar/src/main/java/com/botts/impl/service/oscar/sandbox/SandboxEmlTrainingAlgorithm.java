/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import org.sensorhub.impl.utils.rad.eml.EmlAlarmAssessmentAlgorithm;
import org.sensorhub.impl.utils.rad.eml.EmlEvaluationResult;
import org.sensorhub.impl.utils.rad.eml.EmlLaneParameters;
import org.sensorhub.impl.utils.rad.eml.EmlSourceBreakdown;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic educational {@link EmlAlarmAssessmentAlgorithm} registered ONLY inside
 * the ephemeral Hands-On Training Sandbox ({@code OSCAR_SANDBOX_MODE=true}) when no
 * external EML plugin archive has been uploaded.
 *
 * <ul>
 *   <li>Any occupancy containing a neutron alarm ({@code NA}) fails closed ({@code INVESTIGATE})
 *       so trainees observe that neutron triggers are never auto-released.</li>
 *   <li>Uniform, spatially balanced 4-panel gamma cargo profiles (such as the sandbox's
 *       {@code norm_eml} Potassium-40 fertilizer / ceramic tile injection) return
 *       {@code RELEASE} so OSCAR's real {@code Code 9999: EML Non-Threat} pipeline executes.</li>
 *   <li>Asymmetric localized point-source spikes (such as {@code gamma_alarm}) return
 *       {@code INVESTIGATE} so the alarm stays in the active operator review queue.</li>
 * </ul>
 */
public final class SandboxEmlTrainingAlgorithm implements EmlAlarmAssessmentAlgorithm {

    @Override
    public String getVersionId() {
        return "3.11.10-sandbox-edu";
    }

    @Override
    public String getCollimatedModelId() {
        return "OSCAR-Sandbox-Spatial-Assessor";
    }

    @Override
    public EmlEvaluationResult evaluate(EmlLaneParameters laneParameters, List<String> dailyFileLines) {
        if (dailyFileLines == null || dailyFileLines.isEmpty()) {
            return null;
        }

        boolean hasGammaAlarm = false;
        boolean hasNeutronAlarm = false;
        long occupancyCount = 1L;
        List<int[]> gammaAlarmSlices = new ArrayList<>();

        for (String raw : dailyFileLines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String[] parts = raw.split(",", -1);
            if (parts.length == 0) {
                continue;
            }
            String code = parts[0].trim();
            if ("NA".equals(code)) {
                hasNeutronAlarm = true;
            } else if ("GA".equals(code) && parts.length >= 5) {
                hasGammaAlarm = true;
                try {
                    gammaAlarmSlices.add(new int[]{
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()),
                            Integer.parseInt(parts[3].trim()),
                            Integer.parseInt(parts[4].trim())
                    });
                } catch (NumberFormatException ignored) {
                }
            } else if ("GX".equals(code) && parts.length >= 2) {
                try {
                    occupancyCount = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (!hasGammaAlarm && !hasNeutronAlarm) {
            EmlSourceBreakdown cleanSource = new EmlSourceBreakdown(
                    "NonEmitting", "SandboxSpatialClassifier", 0.0, 4.0, 1.2, 1.5, 0.99, 0.008, 0.002);
            return new EmlEvaluationResult(
                    getVersionId(),
                    getCollimatedModelId(),
                    0.50,
                    laneParameters != null ? laneParameters.portId() : "sandbox",
                    laneParameters != null ? laneParameters.laneId() : 1L,
                    System.currentTimeMillis(),
                    occupancyCount,
                    "NONE",
                    false,
                    false,
                    false,
                    "RELEASE",
                    false,
                    "",
                    0.002,
                    0.998,
                    false,
                    false,
                    5,
                    16.2,
                    "",
                    List.of(cleanSource),
                    cleanSource);
        }

        if (hasNeutronAlarm) {
            EmlSourceBreakdown threatSource = new EmlSourceBreakdown(
                    "Threat", "SandboxNeutronGuard", 2.1, 3.0, 0.8, 1.2, 0.01, 0.02, 0.97);
            return new EmlEvaluationResult(
                    getVersionId(),
                    getCollimatedModelId(),
                    0.50,
                    laneParameters != null ? laneParameters.portId() : "sandbox",
                    laneParameters != null ? laneParameters.laneId() : 1L,
                    System.currentTimeMillis(),
                    occupancyCount,
                    hasGammaAlarm ? "GAMMA_NEUTRON" : "NEUTRON",
                    hasGammaAlarm,
                    true,
                    false,
                    "INVESTIGATE",
                    false,
                    "",
                    0.97,
                    0.03,
                    hasGammaAlarm,
                    true,
                    5,
                    16.2,
                    "Neutron alarm present — human inspection mandatory",
                    List.of(threatSource),
                    threatSource);
        }

        // Distinguish spatially uniform 4-panel distributed NORM cargo from localized point-source spikes
        boolean uniformDistributedNorm = isUniformFourPanelProfile(gammaAlarmSlices);
        if (uniformDistributedNorm) {
            EmlSourceBreakdown normSource = new EmlSourceBreakdown(
                    "NORM", "SandboxSpatialClassifier", 0.5, 14.5, 1.2, 1.8, 0.038, 0.942, 0.020);
            return new EmlEvaluationResult(
                    getVersionId(),
                    getCollimatedModelId(),
                    0.50,
                    laneParameters != null ? laneParameters.portId() : "sandbox",
                    laneParameters != null ? laneParameters.laneId() : 1L,
                    System.currentTimeMillis(),
                    occupancyCount,
                    "GAMMA",
                    true,
                    false,
                    false,
                    "RELEASE",
                    false,
                    "",
                    0.058,
                    0.942,
                    false,
                    false,
                    5,
                    16.2,
                    "",
                    List.of(normSource),
                    normSource);
        }

        EmlSourceBreakdown localizedThreat = new EmlSourceBreakdown(
                "Threat", "SandboxSpatialClassifier", 4.2, 4.9, 0.6, 1.4, 0.034, 0.050, 0.916);
        return new EmlEvaluationResult(
                getVersionId(),
                getCollimatedModelId(),
                0.50,
                laneParameters != null ? laneParameters.portId() : "sandbox",
                laneParameters != null ? laneParameters.laneId() : 1L,
                System.currentTimeMillis(),
                occupancyCount,
                "GAMMA",
                true,
                false,
                false,
                "INVESTIGATE",
                false,
                "",
                0.916,
                0.084,
                true,
                false,
                5,
                16.2,
                "Localized asymmetric gamma hot-spot detected",
                List.of(localizedThreat),
                localizedThreat);
    }

    private static boolean isUniformFourPanelProfile(List<int[]> slices) {
        if (slices.size() < 5) {
            return false;
        }
        int uniformCount = 0;
        for (int[] panel : slices) {
            int min = Math.min(Math.min(panel[0], panel[1]), Math.min(panel[2], panel[3]));
            int max = Math.max(Math.max(panel[0], panel[1]), Math.max(panel[2], panel[3]));
            if (min > 60 && max <= min * 1.35) {
                uniformCount++;
            }
        }
        return uniformCount >= Math.max(5, (int) Math.ceil(slices.size() * 0.75));
    }
}
