/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.pipeline;

import java.nio.file.Path;

/**
 * Immutable per-lane OCR runtime settings.
 */
public record OcrSettings(
        Path modelDir,
        boolean readContainerIds,
        boolean readPlates,
        int framesPerVideo,
        double containerMinConfidence,
        double plateMinConfidence,
        double ignoreTopFraction,
        double ignoreBottomFraction
) {
    public OcrSettings {
        framesPerVideo = Math.max(1, Math.min(12, framesPerVideo));
        containerMinConfidence = clampUnit(containerMinConfidence, 0.50);
        plateMinConfidence = clampUnit(plateMinConfidence, 0.60);
        ignoreTopFraction = clampBand(ignoreTopFraction);
        ignoreBottomFraction = clampBand(ignoreBottomFraction);
    }

    public OcrSettings(Path modelDir, int framesPerVideo, double minConfidence,
                       double ignoreTopFraction, double ignoreBottomFraction) {
        this(modelDir, true, true, framesPerVideo, minConfidence, minConfidence,
                ignoreTopFraction, ignoreBottomFraction);
    }

    private static double clampUnit(double v, double fallback) {
        return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : fallback;
    }

    private static double clampBand(double v) {
        return Double.isFinite(v) ? Math.max(0.0, Math.min(0.45, v)) : 0.0;
    }
}
